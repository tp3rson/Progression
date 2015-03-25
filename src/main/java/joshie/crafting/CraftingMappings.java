package joshie.crafting;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import joshie.crafting.api.CraftingAPI;
import joshie.crafting.api.ICondition;
import joshie.crafting.api.ICraftingMappings;
import joshie.crafting.api.ICriteria;
import joshie.crafting.api.IReward;
import joshie.crafting.api.ITrigger;
import joshie.crafting.api.ITriggerData;
import joshie.crafting.helpers.NBTHelper;
import joshie.crafting.helpers.PlayerHelper;
import joshie.crafting.network.PacketHandler;
import joshie.crafting.network.PacketSyncConditions;
import joshie.crafting.network.PacketSyncSpeed;
import joshie.crafting.network.PacketSyncTriggers;
import joshie.crafting.player.PlayerDataServer;
import joshie.crafting.player.nbt.ConditionNBT;
import joshie.crafting.player.nbt.TriggerNBT;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;

public class CraftingMappings implements ICraftingMappings {
	private PlayerDataServer master;
	private UUID uuid;
	
	protected HashMap<ICriteria, Integer> completedCritera = new HashMap(); //All the completed criteria, with a number for how many times repeated
	protected Set<ITrigger> completedTriggers = new HashSet(); //All the completed trigger, With their unique name as their identifier, Persistent
	protected HashMap<String, ITriggerData> triggerData = new HashMap(); //Unique String > Data mappings for this trigger
	
	//Generated by the remapping
	protected Set<ICriteria> availableCriteria = new HashSet(); //List of all the criteria
	protected Multimap<String, ITrigger> activeTriggers = HashMultimap.create(); //List of all the active triggers, based on their trigger type
	
	//Sets the uuid associated with this class
	public void setMaster(PlayerDataServer master) {
		this.master = master;
		this.uuid = master.getUUID();
	}
	
	@Override
	public void syncToClient(EntityPlayerMP player) {
		remap(); //Remap the data, before the client gets sent the data
		
		PacketHandler.sendToClient(new PacketSyncSpeed(master.getSpeed()), player);
		PacketHandler.sendToClient(new PacketSyncTriggers(true, completedTriggers.toArray(new ITrigger[completedTriggers.size()])), player); //Sync all researches to the client
		PacketHandler.sendToClient(new PacketSyncConditions(true, completedCritera.keySet().toArray(new Integer[completedCritera.size()]), completedCritera.values().toArray(new ICriteria[completedCritera.size()])), player); //Sync all conditions to the client
	}
	
	//Reads the completed criteria
	public void readFromNBT(NBTTagCompound nbt) {
		NBTHelper.readStringCollection(nbt, "Triggers", TriggerNBT.INSTANCE.setCollection(completedTriggers));
		NBTHelper.readMap(nbt, "Criteria", ConditionNBT.INSTANCE.setMap(completedCritera));
		NBTTagList data = nbt.getTagList("TriggerData", 10);
		for (int i = 0; i < data.tagCount(); i++) {
			NBTTagCompound tag = data.getCompoundTagAt(i);
			String name = tag.getString("TriggerName");
			ITrigger trigger = CraftingAPI.registry.getTrigger(null, name, null);
			if (trigger != null) {
				ITriggerData iTriggerData = trigger.newData();
				iTriggerData.readFromNBT(tag);
				triggerData.put(name, iTriggerData);
			}
		}
	}
	
	public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
		NBTHelper.writeCollection(nbt, "Triggers", TriggerNBT.INSTANCE.setCollection(completedTriggers));
		NBTHelper.writeMap(nbt, "Criteria", ConditionNBT.INSTANCE.setMap(completedCritera));
		//Save the extra data for the existing triggers
		NBTTagList data = new NBTTagList();
		for (String name: triggerData.keySet()) {
			ITrigger trigger = CraftingAPI.registry.getTrigger(null, name, null);
			if (trigger != null) {
				NBTTagCompound tag = new NBTTagCompound();
				tag.setString("TriggerName", name);
				ITriggerData iTriggerData = triggerData.get(name);
				iTriggerData.writeToNBT(tag);
				data.appendTag(tag);
			}
		}
		
		nbt.setTag("TriggerData", data);
		return nbt;
	}

	@Override
	public HashMap<ICriteria, Integer> getCompletedCriteria() {
		return completedCritera;
	}

	@Override
	public Set<ITrigger> getCompletedTriggers() {
		return completedTriggers;
	}

	@Override
	public void markCriteriaAsCompleted(boolean overwrite, Integer[] values, ICriteria... conditions) {
		if (overwrite) completedCritera = new HashMap();
		for (int i = 0; i < values.length; i++) {
			completedCritera.put(conditions[i], values[i]);
		}
	}

	@Override
	public void markTriggerAsCompleted(boolean overwrite, ITrigger... researches) {
		if (overwrite) completedTriggers = new HashSet();
		for (ITrigger trigger: researches) {
			completedTriggers.add(trigger);
		}
	}
	
	private boolean containsAny(List<ICriteria> list) {
		for (ICriteria criteria: completedCritera.keySet()) {
			if (completedCritera.keySet().contains(criteria)) return true;
		}
		
		return false;
	}
	
	private ITriggerData getTriggerData(ITrigger trigger) {
		ITriggerData data = triggerData.get(trigger.getUniqueName());
		if (data == null) {
			data = trigger.newData();
			triggerData.put(trigger.getUniqueName(), data);
			return data;
		} else return data;
	}
	
	/** Called to fire a trigger type, Triggers are only ever called on criteria that is activated **/
	@Override
	public boolean fireAllTriggers(String type, Object... data) {				
		EntityPlayer player = PlayerHelper.getPlayerFromUUID(uuid);
		World world = player == null? DimensionManager.getWorld(0): player.worldObj;
		boolean completedAnyCriteria = false;
		Collection<ITrigger> triggers = activeTriggers.get(type);		
				
		
		HashSet<ITrigger> cantContinue = new HashSet();
		for (ITrigger trigger: triggers) {
			Collection<ICondition> conditions = trigger.getConditions();
			for (ICondition condition: conditions) {
				if (!condition.isSatisfied(world, player, uuid)) {
					cantContinue.add(trigger);
					break;
				}
			}
			
			
			if (cantContinue.contains(trigger)) continue; //Grab the old data
			trigger.onFired(getTriggerData(trigger), data); //Fire the new data
			//triggerData.put(trigger.getUniqueName(), newData);
		}
		
		//Next step, now that the triggers have been fire, we need to go through them again
		//Check if they have been satisfied, and if so, mark them as completed triggers
		for (ITrigger trigger: triggers) {
			if (cantContinue.contains(trigger)) continue;
			if (trigger.isCompleted(getTriggerData(trigger))) {
				completedTriggers.add(trigger);
				PacketHandler.sendToClient(new PacketSyncTriggers(false, trigger), uuid);
			}
		}
		
		Multimap<ITrigger, ICriteria> triggerUnlocks = CraftingAPI.registry.getTriggerToCriteria();
		//Next step, now that we have fired the trigger, we need to go through all the active criteria
		//We should check if all triggers have been fulfilled
		for (ITrigger trigger: triggers) {
			if (cantContinue.contains(trigger)) continue;
			Collection<ICriteria> criterian = triggerUnlocks.get(trigger);
			for (ICriteria criteria: criterian) {
				//Check that all triggers are in the completed set
				List<ITrigger> allTriggers = criteria.getTriggers();
				boolean allFired = true;
				for (ITrigger criteriaTrigger: allTriggers) { //the completed triggers map, doesn't contains all the requirements, then we need to remove it
					if (!completedTriggers.contains(criteriaTrigger)) allFired = false;
				}
				
				if (allFired) {
					//Remove the triggers from the active map
					for (ITrigger criteriaTrigger: allTriggers) {
						activeTriggers.get(criteriaTrigger.getTypeName()).remove(criteriaTrigger);
					}
					
					//We have discovered, that every single condition has been fulfilled
					//We can now grab a list of all the Criteria, that gets unlocked by
					//ths criteria being fulfilled, and adding them to the available criteria
					//We should then update the mappings of triggerToCriteria, to include this new info
					Collection<ICriteria> newCriteria = CraftingAPI.registry.getCriteriaUnlocks(criteria);
					for (ICriteria unlocked: newCriteria) {
						//We know what we should unlock, but we don't know if we're the only thing
						//Require to unlock this, so we need to check
						if (completedCritera.keySet().containsAll(unlocked.getRequirements())) {
							//We also need to check it's not blacklisted
							if (!containsAny(unlocked.getConflicts())) {
								availableCriteria.add(unlocked);
								for (ITrigger newTrigger: unlocked.getTriggers()) {
									activeTriggers.put(newTrigger.getTypeName(), newTrigger);
									triggerUnlocks.put(newTrigger, unlocked);
								}
							}
						}
					}
					
					//Finish off a criteria, and remove completed triggers for repeatable
					int amount = getCriteriaCount(criteria);
					amount++;
					completedCritera.put(criteria, amount);
					
					//Mark this critera as completed
					PacketHandler.sendToClient(new PacketSyncConditions(false, new Integer[] { amount }, new ICriteria[] { criteria }), uuid);
					//We have updated the mappings, after firing a trigger
					//Now that we have updated the mappings, we should
					for (IReward reward: criteria.getRewards()) {
						reward.reward(uuid);
					}
					
					completedAnyCriteria = true;
					
					//Cleanup, Remove data for the triggers
					for (ITrigger toCleanup: criteria.getTriggers()) {
						triggerData.remove(toCleanup.getUniqueName());
					}
					
					//Remove the triggers from completed if they are repeatable as well
					int max = criteria.getRepeatAmount();
					if (amount < max) { //Removes all the triggers from completed
						for (ITrigger triggerz: criteria.getTriggers()) {
							completedTriggers.remove(triggerz.getUniqueName());
						}
					}
				}
			}
		}
		
		//Mark data as dirty, whether it changed or not
		CraftingMod.data.markDirty();
		return completedAnyCriteria;
	}
	
	public int getCriteriaCount(ICriteria criteria) {
		int amount = 0;
		Integer last = completedCritera.get(criteria);
		if (last != null) {
			amount = last;
		}
		
		return amount;
	}

	@Override
	public void remap() {
		Collection<ICriteria> allCriteria = CraftingAPI.registry.getCriteria();
		for (ICriteria criteria: allCriteria) {
			//We are now looping though all criteria, we now need to check to see if this
			//First step is to validate to see if this criteria, is available right now
			//If the criteria is repeatable, or is not completed continue
			int max = criteria.getRepeatAmount();
			int last = getCriteriaCount(criteria);
			if (last < max || !completedCritera.keySet().contains(criteria)) {
				if (completedCritera.keySet().containsAll(criteria.getRequirements())) {
					//If we have all the requirements, continue
					//Now that we know that we have all the requirements, we should check for conflicts
					//If it doesn't contain any of the conflicts, continue forwards
					if (!containsAny(criteria.getConflicts())) {
						//The Criteria passed the check for being available, mark it as so
						availableCriteria.add(criteria);
					}
				}
			}
		}
				
		//Now that we have remapped all of the criteria, we should remap the triggers
		for (ICriteria criteria: availableCriteria) {
			List<ITrigger> triggers = criteria.getTriggers(); //Grab a list of all the triggers
			for (ITrigger trigger: triggers) {
				//If we don't have the trigger in the completed map, mark it as available in the active triggers
				if (!completedTriggers.contains(trigger)) {
					activeTriggers.put(trigger.getTypeName(), (ITrigger) trigger);
				}
			}
		}
	}
}
