package org.tmatesoft.svn.core.internal.wc2.old;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;

import org.tmatesoft.svn.core.SVNCommitInfo;
import org.tmatesoft.svn.core.SVNErrorCode;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNProperties;
import org.tmatesoft.svn.core.internal.wc.SVNErrorManager;
import org.tmatesoft.svn.core.internal.wc16.SVNCommitClient16;
import org.tmatesoft.svn.core.internal.wc2.ISvnCommitRunner;
import org.tmatesoft.svn.core.internal.wc2.compat.SvnCodec;
import org.tmatesoft.svn.core.wc.ISVNCommitHandler;
import org.tmatesoft.svn.core.wc.SVNCommitItem;
import org.tmatesoft.svn.core.wc.SVNCommitPacket;
import org.tmatesoft.svn.core.wc2.SvnCommit;
import org.tmatesoft.svn.core.wc2.SvnCommitPacket;
import org.tmatesoft.svn.core.wc2.SvnTarget;
import org.tmatesoft.svn.util.SVNLogType;

public class SvnOldCommit extends SvnOldRunner<Collection<SVNCommitInfo>, SvnCommit> implements ISvnCommitRunner, ISVNCommitHandler {

    public SvnCommitPacket collectCommitItems(SvnCommit operation) throws SVNException {
        setOperation(operation);
        SVNCommitClient16 client = new SVNCommitClient16(getOperation().getRepositoryPool(), getOperation().getOptions());
        client.setEventHandler(getOperation().getEventHandler());
        client.setCommitHandler(this);

        File[] paths = new File[getOperation().getTargets().size()];
        int i = 0;
        for (SvnTarget tgt : getOperation().getTargets()) {
            paths[i++] = tgt.getFile();
        }
        
        String[] changelists = null;
        if (getOperation().getApplicableChangelists() != null && !getOperation().getApplicableChangelists().isEmpty()) {
            changelists = getOperation().getApplicableChangelists().toArray(new String[getOperation().getApplicableChangelists().size()]);
        }
        SVNCommitPacket[] packets = client.doCollectCommitItems(paths, getOperation().isKeepLocks(), false, getOperation().getDepth(), false, changelists);
        return SvnCodec.commitPacket(this, packets);
    }

    @Override
    protected Collection<SVNCommitInfo> run() throws SVNException {
        SvnCommitPacket packet = getOperation().collectCommitItems();
        SVNCommitPacket[] oldPackets = (SVNCommitPacket[]) packet.getLockingContext();
        
        SVNCommitClient16 client = new SVNCommitClient16(getOperation().getRepositoryPool(), getOperation().getOptions());
        client.setEventHandler(getOperation().getEventHandler());
        client.setCommitHandler(this);
        
        SVNCommitInfo[] infos = client.doCommit(oldPackets, getOperation().isKeepLocks(), getOperation().isKeepChangelists(), getOperation().getCommitMessage(), getOperation().getRevisionProperties());
        if (infos != null) {
            if (infos.length == 1 && infos[0].getErrorMessage() != null 
                    && infos[0].getErrorMessage().getErrorCode() != SVNErrorCode.REPOS_POST_COMMIT_HOOK_FAILED) {
                SVNErrorManager.error(infos[0].getErrorMessage(), SVNLogType.WC);
            }
            return Arrays.asList(infos);
        }
        return null;
    }

    public void disposeCommitPacket(Object lockingContext) throws SVNException {
        if (lockingContext instanceof SVNCommitPacket[]) {
            SVNCommitPacket[] packets = (SVNCommitPacket[]) lockingContext;
            for (int i = 0; i < packets.length; i++) {
                try {
                    packets[i].dispose();
                } catch (SVNException e) {
                    //
                }
            }
        }
    }

    public String getCommitMessage(String message, SVNCommitItem[] commitables) throws SVNException {
        return message;
    }

    public SVNProperties getRevisionProperties(String message, SVNCommitItem[] commitables, SVNProperties revisionProperties) throws SVNException {
        return revisionProperties;
    }

}
