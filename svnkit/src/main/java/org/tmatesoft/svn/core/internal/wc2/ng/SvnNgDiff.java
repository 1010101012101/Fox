package org.tmatesoft.svn.core.internal.wc2.ng;

import java.io.File;
import java.util.Collection;

import org.tmatesoft.svn.core.SVNDepth;
import org.tmatesoft.svn.core.SVNErrorCode;
import org.tmatesoft.svn.core.SVNErrorMessage;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNNodeKind;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.internal.util.SVNPathUtil;
import org.tmatesoft.svn.core.internal.wc.ISVNUpdateEditor;
import org.tmatesoft.svn.core.internal.wc.SVNCancellableEditor;
import org.tmatesoft.svn.core.internal.wc.SVNErrorManager;
import org.tmatesoft.svn.core.internal.wc.SVNFileUtil;
import org.tmatesoft.svn.core.internal.wc17.SVNAmbientDepthFilterEditor17;
import org.tmatesoft.svn.core.internal.wc17.SVNReporter17;
import org.tmatesoft.svn.core.internal.wc17.SVNStatusEditor17;
import org.tmatesoft.svn.core.internal.wc17.SVNWCContext;
import org.tmatesoft.svn.core.internal.wc17.db.ISVNWCDb;
import org.tmatesoft.svn.core.internal.wc17.db.Structure;
import org.tmatesoft.svn.core.internal.wc2.SvnRepositoryAccess;
import org.tmatesoft.svn.core.internal.wc2.SvnWcGeneration;
import org.tmatesoft.svn.core.io.ISVNEditor;
import org.tmatesoft.svn.core.io.ISVNReporter;
import org.tmatesoft.svn.core.io.ISVNReporterBaton;
import org.tmatesoft.svn.core.io.SVNCapability;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.wc.SVNRevision;
import org.tmatesoft.svn.core.wc2.SvnDiff;
import org.tmatesoft.svn.core.wc2.SvnTarget;
import org.tmatesoft.svn.util.SVNDebugLog;
import org.tmatesoft.svn.util.SVNLogType;

public class SvnNgDiff extends SvnNgOperationRunner<Void, SvnDiff> {

    @Override
    public boolean isApplicable(SvnDiff operation, SvnWcGeneration wcGeneration) throws SVNException {
        if (wcGeneration != SvnWcGeneration.V17) {
            return false;
        }
        Collection<SvnTarget> targets = operation.getTargets();
        for (SvnTarget target : targets) {
            if (target.isFile()) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected Void run(SVNWCContext context) throws SVNException {
        if (isPeggedDiff()) {
            SvnTarget target = getOperation().getFirstTarget();
            SVNRevision revision1 = getOperation().getStartRevision();
            SVNRevision revision2 = getOperation().getEndRevision();
            SVNRevision pegRevision = target.getPegRevision();

            doDiff(target, revision1, pegRevision, target, revision2);
        } else {
            SvnTarget target1 = getOperation().getFirstTarget();
            SvnTarget target2 = getOperation().getSecondTarget();
            SVNRevision revision1 = getOperation().getStartRevision();
            SVNRevision revision2 = getOperation().getEndRevision();
            SVNRevision pegRevision = SVNRevision.UNDEFINED;

            doDiff(target1, revision1, pegRevision, target2, revision2);
        }

        return null;
    }

    private void doDiff(SvnTarget target1, SVNRevision revision1, SVNRevision pegRevision, SvnTarget target2, SVNRevision revision2) throws SVNException {
        if (revision1 == SVNRevision.UNDEFINED || revision2 == SVNRevision.UNDEFINED) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.CLIENT_BAD_REVISION, "Not all required revisions are specified");
            SVNErrorManager.error(err, SVNLogType.DEFAULT);
        }

        final boolean isLocalRev1 = revision1 == SVNRevision.BASE || revision1 == SVNRevision.WORKING;
        final boolean isLocalRev2 = revision2 == SVNRevision.BASE || revision2 == SVNRevision.WORKING;

        boolean isRepos1;
        boolean isRepos2;

        if (pegRevision != SVNRevision.UNDEFINED) {
            if (isLocalRev1 && isLocalRev2) {
                SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.CLIENT_BAD_REVISION, "At least one revision must be non-local for a pegged diff");
                SVNErrorManager.error(err, SVNLogType.DEFAULT);
            }

            isRepos1 = !isLocalRev1;
            isRepos2 = !isLocalRev2;
        } else {
            isRepos1 = !isLocalRev1 || target1.isURL();
            isRepos2 = !isLocalRev2 || target2.isURL();
        }

        if (isRepos1) {
            if (isRepos2) {
                doDiffReposRepos(target1, revision1, pegRevision, target2, revision2);
            } else {
                doDiffReposWC(target1, revision1, pegRevision, target2, revision2, false);
            }
        } else {
            if (isRepos2) {
                doDiffReposWC(target2, revision2, pegRevision, target1, revision1, true);
            } else {
                doDiffWCWC(target1, revision1, target2, revision2);
            }
        }
    }

    private void doDiffReposRepos(SvnTarget target1, SVNRevision revision1, SVNRevision pegRevision, SvnTarget target2, SVNRevision revision2) throws SVNException {
        SVNURL url1 = getRepositoryAccess().getTargetURL(target1);
        SVNURL url2 = getRepositoryAccess().getTargetURL(target2);

        File basePath = null;
        if (target1.isFile()) {
            basePath = target1.getFile();
        } else if (target2.isFile()) {
            basePath = target2.getFile();
        }

        SVNRepository repository = getRepositoryAccess().createRepository(url2, null, true);
        if (pegRevision != SVNRevision.UNDEFINED) {
            try {
            Structure<SvnRepositoryAccess.LocationsInfo> locations = getRepositoryAccess().getLocations(repository, target2, pegRevision, revision1, revision2);
                url1 = locations.get(SvnRepositoryAccess.LocationsInfo.startUrl);
                url2 = locations.get(SvnRepositoryAccess.LocationsInfo.endUrl);
            } catch (SVNException e) {
                if (e.getErrorMessage().getErrorCode() != SVNErrorCode.CLIENT_UNRELATED_RESOURCES) {
                    throw e;
                }

                //otherwise we ignore the exception
            }

            repository.setLocation(url2, true);
        }

        long revisionNumber2 = getRepositoryAccess().getRevisionNumber(repository, target2, revision2, null).lng(SvnRepositoryAccess.RevisionsPair.revNumber);
        SVNNodeKind kind2 = repository.checkPath("", revisionNumber2);

        repository.setLocation(url1, true);
        final long revisionNumber1 = getRepositoryAccess().getRevisionNumber(repository, target1, revision1, null).lng(SvnRepositoryAccess.RevisionsPair.revNumber);
        SVNNodeKind kind1 = repository.checkPath("", revisionNumber1);

        if (kind1 == SVNNodeKind.NONE && kind2 == SVNNodeKind.NONE) {
            if (url1.equals(url2)) {
                SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_NOT_FOUND,
                        "Diff target ''{0}'' was not found in the " +
                                "repository at revisions ''{1}'' and ''{2}''", new Object[]{
                        url1, new Long(revisionNumber1), new Long(revisionNumber2)
                });
                SVNErrorManager.error(err, SVNLogType.WC);
            } else {
                SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_NOT_FOUND,
                        "Diff targets ''{0}'' and ''{1}'' were not found " +
                                "in the repository at revisions ''{2}'' and " +
                                "''{3}''", new Object[]{
                        url1, url2, new Long(revisionNumber1), new Long(revisionNumber2)
                });
                SVNErrorManager.error(err, SVNLogType.WC);
            }
        } else if (kind1 == SVNNodeKind.NONE) {
            checkDiffTargetExists(url1, revisionNumber2, revisionNumber1, repository);
        } else if (kind2 == SVNNodeKind.NONE) {
            checkDiffTargetExists(url2, revisionNumber1, revisionNumber2, repository);
        }

        SVNNodeKind kind;
        SVNURL anchor1 = url1;
        SVNURL anchor2 = url2;
        String targetString1 = "";
        String targetString2 = "";

        if (kind1 == SVNNodeKind.NONE && kind2 == SVNNodeKind.NONE) {
            SVNURL repositoryRoot = repository.getRepositoryRoot(true);
            SVNURL newAnchor = kind1 == SVNNodeKind.NONE ? anchor1 : anchor2;
            SVNRevision revision = kind1 == SVNNodeKind.NONE ? revision1 : revision2;
            do  {
                if (!newAnchor.equals(repositoryRoot)) {
                    newAnchor = SVNURL.parseURIEncoded(SVNPathUtil.removeTail(newAnchor.toString()));
                    if (basePath != null) {
                        basePath = SVNFileUtil.getParentFile(basePath);
                    }
                }
                repository.setLocation(newAnchor, false);
                kind = repository.checkPath("", revision.getNumber()); //TODO: no such method: SVNRepository#checkPath(String, SVNRevision)
            } while (kind1 != SVNNodeKind.DIR);

            anchor1 = anchor2 = newAnchor;

            targetString1 = SVNPathUtil.getRelativePath(newAnchor.toDecodedString(), url1.toDecodedString());
            targetString2 = SVNPathUtil.getRelativePath(newAnchor.toDecodedString(), url2.toDecodedString());

            assert target1 != null && target2 != null;

            if (kind1 == SVNNodeKind.NONE) {
                kind1 = SVNNodeKind.DIR;
            } else {
                kind2 = SVNNodeKind.DIR;
            }
        } else if (kind1 == SVNNodeKind.FILE || kind2 == SVNNodeKind.FILE) {
            targetString1 = SVNPathUtil.tail(url1.toDecodedString());
            targetString2 = SVNPathUtil.tail(url2.toDecodedString());

            anchor1 = SVNURL.parseURIEncoded(SVNPathUtil.removeTail(url1.toString()));
            anchor2 = SVNURL.parseURIEncoded(SVNPathUtil.removeTail(url2.toString()));

            if (basePath != null) {
                basePath = SVNFileUtil.getParentFile(basePath);
            }
            repository.setLocation(anchor1, false);
        }

        ISvnDiffGenerator generator = getDiffGenerator();
        generator.init(url1.toString(), url2.toString());

        SvnDiffCallback callback = createDiffCallback(generator, false, revisionNumber1, revisionNumber2);
        SVNRepository extraRepository = getRepositoryAccess().createRepository(anchor1, null, false);

        ISVNEditor editor;
        editor = SvnNgRemoteDiffEditor.createEditor(getWcContext(), basePath, getOperation().getDepth(), extraRepository, revisionNumber1, true, false, callback, this);
        editor = SVNCancellableEditor.newInstance(editor, this, SVNDebugLog.getDefaultLog());

        ISVNReporterBaton reporter = new ISVNReporterBaton() {

            public void report(ISVNReporter reporter) throws SVNException {
                reporter.setPath("", null, revisionNumber1, SVNDepth.INFINITY, false);
                reporter.finishReport();
            }
        };

        repository.diff(url2, revisionNumber2, revisionNumber1, targetString1,
                getOperation().isIgnoreAncestry(), getOperation().getDepth(), true, reporter, editor);

        //TODO: is cleanup required? editor could cleanup itself by putting cleanup function to closeEdit and abortEdit
    }

    private void doDiffReposWC(SvnTarget target1, SVNRevision revision1, SVNRevision pegRevision, SvnTarget target2, SVNRevision revision2, boolean reverse) throws SVNException {
        assert !target2.isURL();

        SVNURL url1 = getRepositoryAccess().getTargetURL(target1);
        String target = getWcContext().getActualTarget(target2.getFile());
        File anchor;
        if (target == null || target.length() == 0) {
            anchor = target2.getFile();
        } else {
            anchor = SVNFileUtil.getParentFile(target2.getFile());
        }
        SVNURL anchorUrl = getWcContext().getNodeUrl(anchor);

        if (anchorUrl == null) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.ENTRY_MISSING_URL, "Directory ''{0}'' has no URL", anchor);
            SVNErrorManager.error(err, SVNLogType.DEFAULT);
        }

        final ISvnDiffGenerator generator = getDiffGenerator();

        if (pegRevision != SVNRevision.UNDEFINED) {
            url1 = getRepositoryAccess().getLocations(null, target1, pegRevision, revision1, SVNRevision.UNDEFINED).get(SvnRepositoryAccess.LocationsInfo.startUrl);

            if (!reverse) {
                generator.init(url1.toString(), anchorUrl.toString());
            } else {
                generator.init(anchorUrl.toString(), url1.toString());
            }
        } else {
            if (!reverse) {
                generator.init(target1.getPathOrUrlString(), target2.getPathOrUrlString());
            } else {
                generator.init(target2.getPathOrUrlString(), target1.getPathOrUrlString());
            }
        }

        SVNRepository repository2 = getRepositoryAccess().createRepository(anchorUrl, null, true);

        if (getOperation().isUseGitDiffFormat()) {
            File wcRoot = getWcContext().getDb().getWCRoot(anchor);
            //TODO: use wcRoot
        }

        boolean serverSupportsDepth = repository2.hasCapability(SVNCapability.DEPTH);

        long revisionNumber1 = getRepositoryAccess().getRevisionNumber(repository2, url1.equals(target1.getURL()) ? null : target1, revision1, null).lng(SvnRepositoryAccess.RevisionsPair.revNumber);

        SvnDiffCallback callback = createDiffCallback(generator, reverse, revisionNumber1, -1);

        SVNReporter17 reporter = new SVNReporter17(target2.getFile(), getWcContext(), false, !serverSupportsDepth, getOperation().getDepth(), false, false, true, false, SVNDebugLog.getDefaultLog());
        ISVNEditor editor = createDiffEditor(anchor, target, reverse, isRevisionBase(revision2), callback, serverSupportsDepth);
        repository2.diff(url1, revisionNumber1, revisionNumber1, target, getOperation().isIgnoreAncestry(), getDiffDepth(getOperation().getDepth()), true, reporter, editor);
    }

    private void doDiffWCWC(SvnTarget target1, SVNRevision revision1, SvnTarget target2, SVNRevision revision2) throws SVNException {
        assert (!target1.isURL());
        assert (!target2.isURL());

        if (!target1.getFile().equals(target2.getFile()) || !(revision1 == SVNRevision.BASE && revision2 == SVNRevision.WORKING)) {
            SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.INCORRECT_PARAMS, "Only diffs between a path's text-base and its working files are supported at this time");
            SVNErrorManager.error(err, SVNLogType.DEFAULT);
        }

        long revisionNumber1;
        try {
            revisionNumber1 = getRepositoryAccess().getRevisionNumber(null, target1, revision1, null).lng(SvnRepositoryAccess.RevisionsPair.revNumber);
        } catch (SVNException e) {
            if (e.getErrorMessage().getErrorCode() == SVNErrorCode.CLIENT_BAD_REVISION) {
                revisionNumber1 = 0;
            } else {
                throw e;
            }
        }

        File anchor;
        SVNNodeKind kind = getWcContext().readKind(target1.getFile(), false);
        if (kind == SVNNodeKind.DIR) {
            anchor = SVNFileUtil.getParentFile(target1.getFile());
        } else {
            anchor = target1.getFile();
        }

        final ISvnDiffGenerator generator = getDiffGenerator();
        generator.init(target1.getFile().getPath(), target2.getFile().getPath());
        final SvnDiffCallback callback = createDiffCallback(generator, false, revisionNumber1, -1);

        doDiffWC(target1.getFile(), callback);
    }

    private void doDiffWC(File localAbspath, ISvnDiffCallback callback) throws SVNException {
        ISVNWCDb.SVNWCDbKind kind = getWcContext().getDb().readKind(localAbspath, false);
        File anchorAbspath;
        if (kind == ISVNWCDb.SVNWCDbKind.Dir) {
            anchorAbspath = localAbspath;
        } else {
            anchorAbspath = SVNFileUtil.getParentFile(localAbspath);
        }

        final boolean getAll;
        //noinspection RedundantIfStatement
        if (getOperation().isShowCopiesAsAdds() || getOperation().isUseGitDiffFormat()) {
            getAll = true;
        } else {
            getAll = false;
        }

        final boolean diffIgnored = false;

        final SvnDiffStatusReceiver statusHandler = new SvnDiffStatusReceiver(getWcContext(), localAbspath, getWcContext().getDb(), callback, getOperation().isIgnoreAncestry(), getOperation().isShowCopiesAsAdds(), getOperation().isUseGitDiffFormat());
        final SVNStatusEditor17 statusEditor = new SVNStatusEditor17(
                localAbspath,
                getWcContext(),
                getOperation().getOptions(),
                !diffIgnored,
                getAll,
                getOperation().getDepth(),
                statusHandler);
        statusEditor.walkStatus(localAbspath, getOperation().getDepth(), getAll, !diffIgnored, false, getOperation().getApplicableChangelists());

    }

    private void checkDiffTargetExists(SVNURL url, long revision, long otherRevision, SVNRepository repository) throws SVNException {
        repository.setLocation(url, true);
        SVNNodeKind kind = repository.checkPath("", revision);
        if (kind == SVNNodeKind.NONE) {
            if (revision == otherRevision) {
                SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_NOT_FOUND,
                        "Diff target ''{0}'' was not found in the " +
                                "repository at revision ''{1}''", new Object[]{
                        url, new Long(revision)
                });
                SVNErrorManager.error(err, SVNLogType.WC);
            } else {
                SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.FS_NOT_FOUND,
                        "Diff target ''{0}'' was not found in the " +
                                "repository at revisions ''{1}'' and ''{2}''", new Object[]{
                        url, new Long(revision), new Long(otherRevision)
                });
                SVNErrorManager.error(err, SVNLogType.WC);
            }
        }
    }

    private boolean isPeggedDiff() {
        return getOperation().getTargets().size() == 1;
    }

    private ISVNEditor createDiffEditor(File anchorAbspath, String target, boolean reverse, boolean revisionIsBase, ISvnDiffCallback callback, boolean serverSupportsDepth) {
        ISVNUpdateEditor editor = new SvnDiffEditor(anchorAbspath, target, callback, getOperation().getDepth(), getWcContext(), reverse, revisionIsBase, getOperation().isShowCopiesAsAdds(), getOperation().isIgnoreAncestry(), getOperation().getApplicableChangelists(), getOperation().isUseGitDiffFormat(), this);
        if (!serverSupportsDepth && getOperation().getDepth() == SVNDepth.UNKNOWN) {
            editor = new SVNAmbientDepthFilterEditor17(editor, getWcContext(), anchorAbspath, target, revisionIsBase);
        }
        return SVNCancellableEditor.newInstance(editor, this, SVNDebugLog.getDefaultLog());
    }

    private boolean isRevisionBase(SVNRevision revision2) {
        return revision2 == SVNRevision.BASE;
    }

    private SVNDepth getDiffDepth(SVNDepth depth) {
        return depth != SVNDepth.INFINITY ? depth : SVNDepth.UNKNOWN;
    }

    private SvnDiffCallback createDiffCallback(ISvnDiffGenerator generator, boolean reverse, long revisionNumber1, long revisionNumber2) {
        SvnDiffCallback callback = new SvnDiffCallback(generator, reverse ? revisionNumber2 : revisionNumber1, reverse ? revisionNumber1 : revisionNumber2, getOperation().getOutput());
        callback.setBasePath(new File("").getAbsoluteFile());
        return callback;
    }

    private ISvnDiffGenerator getDiffGenerator() {
        ISvnDiffGenerator diffGenerator = getOperation().getDiffGenerator();
        if (diffGenerator != null) {
            return diffGenerator;
        } else {
            SvnDiffGenerator svnDiffGenerator = new SvnDiffGenerator();
            svnDiffGenerator.setUseGitFormat(getOperation().isUseGitDiffFormat());
            return svnDiffGenerator;
        }
    }
}
