package hudson.plugins.scm;
import hudson.scm.SCM;
import org.jenkinsci.plugins.workflow.steps.scm.SCMStep;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.export.Exported;

import javax.annotation.Nonnull;

public class ArtifactorySCMStep extends SCMStep {

    private String url;
    private String latestVersionTag;

    /** The clear workspace. */
    private boolean clearWorkspace;
    //private boolean useCache;

    private String credentialsId;

    @DataBoundConstructor
    public ArtifactorySCMStep() {
    }

    @DataBoundSetter
    public void setUrl(String url) {
        this.url = url;
    }

    @Exported
    public String getUrl() {
        return url;
    }

    @DataBoundSetter
    public void setClearWorkspace(boolean clearWorkspace) {
        this.clearWorkspace = clearWorkspace;
    }

    @Exported
    public boolean isClearWorkspace() {
        return clearWorkspace;
    }

    /*
    @DataBoundSetter
    public void setUseCache(boolean useCache) {
        this.useCache = useCache;
    }

    @Exported
    public boolean isUseCache() {
        return useCache;
    }
     */

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = credentialsId;
    }

    @Exported
    public String getCredentialsId() {
        return credentialsId;
    }

    @DataBoundSetter
    public void setLatestVersionTag(String latestVersionTag) {
        this.latestVersionTag = latestVersionTag;
    }

    @Exported
    public String getLatestVersionTag() {
        return latestVersionTag;
    }

    @Nonnull
    @Override
    protected SCM createSCM() {
        return new ArtifactorySCM(url, latestVersionTag, clearWorkspace, credentialsId);
    }

}
