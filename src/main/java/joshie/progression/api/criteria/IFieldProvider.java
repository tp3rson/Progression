package joshie.progression.api.criteria;

import java.util.UUID;

public interface IFieldProvider {
    /** Return an unlocalised name **/
    public String getUnlocalisedName();
    
    /** Return the localised name for this thing **/
    public String getLocalisedName();

    /** Return the uniqueid for this object **/
    public UUID getUniqueID();
    
    /** Return a colour to display this thingy as **/
    public int getColor();

    /** Return a description of this thingy,
     *  this is to be displayed in display mode */
    public String getDescription();

    /** Called before anything is drawn
     *  Use this if you need to perform checks,
     *  Or validate things before stuff is drawn*/
    public void updateDraw();
}
