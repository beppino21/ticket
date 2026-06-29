package eone.ticket.view.managedbeans;

import org.eclnt.workplace.IWorkpageContainer;
import org.eclnt.workplace.WorkpageDispatcher;

/*
 * The dispatcher is referenced in faces-config.xml. When changing the package
 * of the dispatcher, then also update the faces-config.xml link!
 */
public class Dispatcher extends WorkpageDispatcher
{
    // ------------------------------------------------------------------------
    // constructors
    // ------------------------------------------------------------------------

    /** 
     * Constructor that is used for the root dispatcher, e.g. "#{d}". 
     */
    public Dispatcher()
    {
        // add any implementation...
    }

    /** 
     * Dispatcher that is used for the sub dispatcher objects, e.g. "#{d.d_1}". 
     */
    public Dispatcher(IWorkpageContainer workpageContainer)
    {
        super(workpageContainer);
        // add any implementation...
    }
    
    // ------------------------------------------------------------------------
    // public usage
    // ------------------------------------------------------------------------

    /**
     * This method needs to be implemented if you want to extend the information
     * within the page bean browser tool. Otherwise leave unchanged.
     */
    public static DispatcherInfo getStaticDispatcherInfo() { return new DispatcherInfo(Dispatcher.class); }
    
    /** 
     * Convenience access to access the root dispatcher in the current dialog session. 
     */
    public static Dispatcher getDialogSessionInstance() 
    {
        // correctly casting the generic dispatcher into this class
        return (Dispatcher)WorkpageDispatcher.getDialogSessionInstance();
    }
}
