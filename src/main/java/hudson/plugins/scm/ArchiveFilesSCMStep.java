package hudson.plugins.scm;
import hudson.scm.SCM;
import org.jenkinsci.plugins.workflow.steps.scm.SCMStep;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.export.Exported;

import javax.annotation.Nonnull;

public class ArchiveFilesSCMStep extends SCMStep {

    private String url;

    /** The clear workspace. */
    private boolean clearWorkspace;

    private String credentialsId;

    @DataBoundConstructor
    public ArchiveFilesSCMStep() {
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

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = credentialsId;
    }

    @Exported
    public String getCredentialsId() {
        return credentialsId;
    }

    @Nonnull
    @Override
    protected SCM createSCM() {
        return new ArchiveFilesSCM(url, clearWorkspace, credentialsId);
    }

}
