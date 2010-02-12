/*
 * ====================================================================
 * Copyright (c) 2004-2010 TMate Software Ltd.  All rights reserved.
 *
 * This software is licensed as described in the file COPYING, which
 * you should have received as part of this distribution.  The terms
 * are also available at http://svnkit.com/license.html.
 * If newer versions of this license are posted there, you may use a
 * newer version instead, at your option.
 * ====================================================================
 */
package org.tmatesoft.svn.core.wc;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;

import org.tmatesoft.svn.core.SVNErrorCode;
import org.tmatesoft.svn.core.SVNErrorMessage;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.internal.wc.SVNErrorManager;
import org.tmatesoft.svn.core.internal.wc.admin.SVNAdminArea;
import org.tmatesoft.svn.core.internal.wc.admin.SVNWCAccess;
import org.tmatesoft.svn.core.internal.wc.patch.SVNPatch;
import org.tmatesoft.svn.core.internal.wc.patch.SVNPatchFileStream;
import org.tmatesoft.svn.core.internal.wc.patch.SVNPatchTarget;
import org.tmatesoft.svn.util.SVNLogType;

/**
 * @version 1.3
 * @author TMate Software Ltd.
 */
public class SVNPatchClient extends SVNBasicClient {

    public SVNPatchClient(ISVNRepositoryPool repositoryPool, ISVNOptions options) {
        super(repositoryPool, options);
    }

    public void doPatch(File absPatchPath, File localAbsPath, boolean dryRun, int stripCount) throws SVNException, IOException {

        if (stripCount < 0) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.INCORRECT_PARAMS, "strip count must be positive");
            SVNErrorManager.error(err, SVNLogType.CLIENT);
        }

        final SVNWCAccess wcAccess = createWCAccess();
        try {
            wcAccess.setEventHandler(this);
            final SVNAdminArea wc = wcAccess.open(localAbsPath, true, SVNWCAccess.INFINITE_DEPTH);
            applyPatches(absPatchPath, localAbsPath, dryRun, stripCount, wc);
        } finally {
            wcAccess.close();
        }

    }

    private void applyPatches(File absPatchPath, File absWCPath, boolean dryRun, int stripCount, SVNAdminArea wc) throws SVNException, IOException {

        final SVNPatchFileStream patchFile = SVNPatchFileStream.openReadOnly(absPatchPath);

        try {

            final List targets = new ArrayList();

            SVNPatch patch;
            do {
                checkCancelled();
                patch = SVNPatch.parseNextPatch(patchFile);
                if (patch != null) {
                    final SVNPatchTarget target = SVNPatchTarget.applyPatch(patch, absWCPath, stripCount, wc);
                    targets.add(target);
                }
            } while (patch != null);

            /* Install patched targets into the working copy. */
            for (Iterator i = targets.iterator(); i.hasNext();) {
                checkCancelled();
                final SVNPatchTarget target = (SVNPatchTarget) i.next();
                if (!target.isSkipped()) {
                    target.installPatchedTarget(absWCPath, dryRun, wc);
                }
                target.sendPatchNotification(wc);
                target.getPatch().close();
            }

        } catch (IOException e) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.IO_ERROR, e.getMessage());
            SVNErrorManager.error(err, Level.FINE, SVNLogType.WC);
        } finally {
            if (patchFile != null) {
                patchFile.close();
            }
        }

    }

}