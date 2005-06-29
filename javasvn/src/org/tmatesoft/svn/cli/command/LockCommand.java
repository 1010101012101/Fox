/*
 * Created on 28.04.2005
 */
package org.tmatesoft.svn.cli.command;

import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;

import org.tmatesoft.svn.cli.SVNArgument;
import org.tmatesoft.svn.cli.SVNCommand;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.wc.SVNWCClient;

public class LockCommand extends SVNCommand {

    public void run(PrintStream out, PrintStream err) throws SVNException {
        boolean force = getCommandLine().hasArgument(SVNArgument.FORCE);
        String message = (String) getCommandLine().getArgumentValue(SVNArgument.MESSAGE);
        
        Collection files = new ArrayList();
        for (int i = 0; i < getCommandLine().getPathCount(); i++) {
            files.add(new File(getCommandLine().getPathAt(i)));
        }
        File[] filesArray = (File[]) files.toArray(new File[files.size()]);
        if (filesArray.length > 0) {
            SVNWCClient wcClient = new SVNWCClient(getOptions(), new SVNCommandEventProcessor(out, err, false));
            wcClient.doLock(filesArray, force, message);
        }
        files.clear();
        
        for (int i = 0; i < getCommandLine().getURLCount(); i++) {
            files.add(getCommandLine().getURL(i));
        }
        String[] urls = (String[]) files.toArray(new String[files.size()]);
        if (urls.length > 0) {
            SVNWCClient wcClient = new SVNWCClient(getOptions(), new SVNCommandEventProcessor(out, err, false));
            wcClient.doLock(urls, force, message);
        }
    }
}
