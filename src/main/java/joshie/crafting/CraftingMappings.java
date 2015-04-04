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
import joshie.crafting.network.PacketSyncAbilities;
import joshie.crafting.network.PacketSyncCriteria;
import joshie.crafting.network.PacketSyncTriggers;
import joshie.crafting.network.PacketSyncTriggers.SyncPair;
import joshie.crafting.player.PlayerDataServer;
import joshie.crafting.player.nbt.CriteriaNBT;
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
    protected HashMap<ITrigger, ITriggerData> triggerData = new HashMap(); //Unique String > Data mappings for this trigger

    //Generated by the remapping
    protected Multimap<String, ITrigger> activeTriggers; //List of all the active triggers, based on their trigger type

    //Sets the uuid associated with this class
    public void setMaster(PlayerDataServer master) {
        this.master = master;
        this.uuid = master.getUUID();
    }

    @Override
    public void syncToClient(EntityPlayerMP player) {
        //remap(); //Remap the data, before the client gets sent the data

        PacketHandler.sendToClient(new PacketSyncAbilities(master.getAbilities()), player);
        SyncPair[] values = new SyncPair[CraftAPIRegistry.criteria.size()];
        int pos = 0;
        for (ICriteria criteria : CraftAPIRegistry.criteria.values()) {
            int[] numbers = new int[criteria.getTriggers().size()];
            for (int i = 0; i < criteria.getTriggers().size(); i++) {
                numbers[i] = i;
            }

            values[pos] = new SyncPair(criteria, numbers);

            pos++;
        }

        PacketHandler.sendToClient(new PacketSyncTriggers(values), player); //Sync all researches to the client

        if (completedCritera.size() > 0) {
            PacketHandler.sendToClient(new PacketSyncCriteria(true, completedCritera.values().toArray(new Integer[completedCritera.size()]), completedCritera.keySet().toArray(new ICriteria[completedCritera.size()])), player); //Sync all conditions to the client
        }
    }

    //Reads the completed criteria
    public void readFromNBT(NBTTagCompound nbt) {
        NBTHelper.readTagCollection(nbt, "Completed Triggers", TriggerNBT.INSTANCE.setCollection(completedTriggers));
        NBTHelper.readMap(nbt, "Completed Criteria", CriteriaNBT.INSTANCE.setMap(completedCritera));
        NBTTagList data = nbt.getTagList("Active Trigger Data", 10);
        for (int i = 0; i < data.tagCount(); i++) {
            NBTTagCompound tag = data.getCompoundTagAt(i);
            String name = tag.getString("Name");
            ICriteria criteria = CraftingAPI.registry.getCriteriaFromName(name);
            if (criteria != null) {
                for (ITrigger trigger : criteria.getTriggers()) {
                    ITriggerData iTriggerData = trigger.newData();
                    iTriggerData.readFromNBT(tag);
                    triggerData.put(trigger, iTriggerData);
                }
            }
        }
    }

    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTHelper.writeCollection(nbt, "Completed Triggers", TriggerNBT.INSTANCE.setCollection(completedTriggers));
        NBTHelper.writeMap(nbt, "Completed Criteria", CriteriaNBT.INSTANCE.setMap(completedCritera));
        //Save the extra data for the existing triggers
        NBTTagList data = new NBTTagList();
        for (ITrigger trigger : triggerData.keySet()) {
            if (trigger != null) {
                NBTTagCompound tag = new NBTTagCompound();
                tag.setString("Name", trigger.getCriteria().getUniqueName());
                ITriggerData iTriggerData = triggerData.get(trigger);
                iTriggerData.writeToNBT(tag);
                data.appendTag(tag);
            }
        }

        nbt.setTag("Active Trigger Data", data);
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
    public void markTriggerAsCompleted(boolean overwrite, SyncPair[] pairs) {
        if (overwrite) completedTriggers = new HashSet();
        for (SyncPair pair : pairs) {
            if (pair == null || pair.criteria == null) continue; //Avoid broken pairs
            for (int i = 0; i < pair.triggers.length; i++) {
                int num = pair.triggers[i];
                completedTriggers.add(pair.criteria.getTriggers().get(num));
            }
        }
    }

    private boolean containsAny(List<ICriteria> list) {
        for (ICriteria criteria : list) {
            if (completedCritera.keySet().contains(criteria)) return true;
        }

        return false;
    }

    private ITriggerData getTriggerData(ITrigger trigger) {
        ITriggerData data = triggerData.get(trigger);
        if (data == null) {
            data = trigger.newData();
            triggerData.put(trigger, data);
            return data;
        } else return data;
    }

    /** Called to fire a trigger type, Triggers are only ever called on criteria that is activated **/
    @Override
    public boolean fireAllTriggers(String type, Object... data) {
        if (activeTriggers == null) return false; //If the remapping hasn't occured yet, say goodbye!

        EntityPlayer player = PlayerHelper.getPlayerFromUUID(uuid);
        World world = player == null ? DimensionManager.getWorld(0) : player.worldObj;
        boolean completedAnyCriteria = false;
        Collection<ITrigger> triggers = activeTriggers.get(type);
        HashSet<ITrigger> cantContinue = new HashSet();
        for (ITrigger trigger : triggers) {
            Collection<ICondition> conditions = trigger.getConditions();
            for (ICondition condition : conditions) {
                if (condition.isSatisfied(world, player, uuid) == condition.isInverted()) {
                    cantContinue.add(trigger);
                    break;
                }
            }

            if (cantContinue.contains(trigger)) continue; //Grab the old data
            trigger.onFired(uuid, getTriggerData(trigger), data); //Fire the new data
        }

        //Next step, now that the triggers have been fire, we need to go through them again
        //Check if they have been satisfied, and if so, mark them as completed triggers
        HashSet<ITrigger> toRemove = new HashSet();
        for (ITrigger trigger : triggers) {
            if (cantContinue.contains(trigger)) continue;
            if (trigger.isCompleted(getTriggerData(trigger))) {
                completedTriggers.add(trigger);
                toRemove.add(trigger);
                PacketHandler.sendToClient(new PacketSyncTriggers(trigger.getCriteria(), trigger.getInternalID()), uuid);
            }
        }

        //Remove completed triggers from the active map
        for (ITrigger trigger : toRemove) {
            triggers.remove(toRemove);
        }

        //Create a list of new triggers to add to the active trigger map
        HashSet<ITrigger> forRemovalFromActive = new HashSet();
        HashSet<ITrigger> forRemovalFromCompleted = new HashSet();
        HashSet<ICriteria> toRemap = new HashSet();

        //Next step, now that we have fired the trigger, we need to go through all the active criteria
        //We should check if all triggers have been fulfilled
        for (ITrigger trigger : triggers) {
            if (cantContinue.contains(trigger) || trigger.getCriteria() == null) continue;
            ICriteria criteria = trigger.getCriteria();
            //Check that all triggers are in the completed set
            List<ITrigger> allTriggers = criteria.getTriggers();
            boolean allFired = true;
            for (ITrigger criteriaTrigger : allTriggers) { //the completed triggers map, doesn't contains all the requirements, then we need to remove it
                if (!completedTriggers.contains(criteriaTrigger)) allFired = false;
            }

            if (allFired) {
                //We have completed this criteria we now to remap everything
                //First step, Complete the criteria
                completedAnyCriteria = true;
                int completedTimes = getCriteriaCount(criteria);
                completedTimes++;
                completedCritera.put(criteria, completedTimes);
                //Now that we have updated how times we have completed this quest
                //We should mark all the triggers for removal from activetriggers, as well as actually remove their stored data
                for (ITrigger criteriaTrigger : allTriggers) {
                    forRemovalFromActive.add(criteriaTrigger);
                    //Remove all the conflicts triggers
                    for (ICriteria conflict : criteria.getConflicts()) {
                        forRemovalFromActive.addAll(conflict.getTriggers());
                    }

                    triggerData.remove(criteriaTrigger);
                }

                //Now that we have removed all the triggers, and marked this as completed, we should give out the rewards
                for (IReward reward : criteria.getRewards()) {
                    reward.reward(uuid);
                }

                //The next step in the process is to update the active trigger maps for everything
                //That we unlock with this criteria have been completed
                toRemap.add(criteria);

                if (completedTimes == 1) { //Only do shit if this is the first time it was completed                    
                    toRemap.addAll(CraftingRemapper.criteriaToUnlocks.get(criteria));
                }

                PacketHandler.sendToClient(new PacketSyncCriteria(false, new Integer[] { completedTimes }, new ICriteria[] { criteria }), uuid);
            }
        }

        //Removes all the triggers from the active map
        for (ITrigger trigger : forRemovalFromActive) {
            activeTriggers.get(trigger.getTypeName()).remove(trigger);
        }

        //Remap the criteria
        for (ICriteria criteria : toRemap) {
            remapCriteriaOnCompletion(criteria);
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

    private void remapCriteriaOnCompletion(ICriteria criteria) {
        ICriteria available = null;
        //We are now looping though all criteria, we now need to check to see if this
        //First step is to validate to see if this criteria, is available right now
        //If the criteria is repeatable, or is not completed continue
        int max = criteria.getRepeatAmount();
        int last = getCriteriaCount(criteria);
        if (last < max) {
            if (completedCritera.keySet().containsAll(criteria.getRequirements())) {
                //If we have all the requirements, continue
                //Now that we know that we have all the requirements, we should check for conflicts
                //If it doesn't contain any of the conflicts, continue forwards
                if (!containsAny(criteria.getConflicts())) {
                    //The Criteria passed the check for being available, mark it as so
                    available = criteria;
                }
            }

            //If we are allowed to redo triggers, remove from completed
            completedTriggers.removeAll(criteria.getTriggers());
        }

        if (available != null) {
            List<ITrigger> triggers = criteria.getTriggers(); //Grab a list of all the triggers
            for (ITrigger trigger : triggers) {
                //If we don't have the trigger in the completed map, mark it as available in the active triggers
                if (!completedTriggers.contains(trigger)) {
                    activeTriggers.get(trigger.getTypeName()).add((ITrigger) trigger);
                }
            }
        }
    }

    @Override
    public void remap() {
        Set<ICriteria> availableCriteria = new HashSet(); //Recreate the available mappings
        activeTriggers = HashMultimap.create(); //Recreate the trigger mappings

        Collection<ICriteria> allCriteria = CraftAPIRegistry.criteria.values();
        for (ICriteria criteria : allCriteria) {
            //We are now looping though all criteria, we now need to check to see if this
            //First step is to validate to see if this criteria, is available right now
            //If the criteria is repeatable, or is not completed continue
            int max = criteria.getRepeatAmount();
            int last = getCriteriaCount(criteria);
            if (last < max) {
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
        for (ICriteria criteria : availableCriteria) {
            List<ITrigger> triggers = criteria.getTriggers(); //Grab a list of all the triggers
            for (ITrigger trigger : triggers) {
                //If we don't have the trigger in the completed map, mark it as available in the active triggers
                if (!completedTriggers.contains(trigger)) {
                    activeTriggers.get(trigger.getTypeName()).add((ITrigger) trigger);
                }
            }
        }
    }
}
