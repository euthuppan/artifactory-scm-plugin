package hudson.plugins.scm;

import static com.cloudbees.plugins.credentials.CredentialsProvider.*;
import static hudson.FilePath.TarCompression.GZIP;
import static hudson.FilePath.TarCompression.NONE;
import static java.util.logging.Level.ALL;
import static java.util.logging.Level.INFO;

import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;

import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.*;
import hudson.scm.ChangeLogParser;
import hudson.scm.NullChangeLogParser;
import hudson.scm.PollingResult;
import hudson.scm.SCMDescriptor;
import hudson.scm.SCMRevisionState;
import hudson.scm.SCM;
import hudson.security.ACL;
import hudson.util.FormValidation;


import java.io.*;
import java.net.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.Comparator;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.servlet.ServletException;

import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.CountingInputStream;
import org.jvnet.robust_http_client.RetryableHttpStream;
import org.kohsuke.stapler.*;
import org.kohsuke.stapler.export.Exported;

//import sun.net.www.protocol.http.AuthCacheImpl;
//import sun.net.www.protocol.http.AuthCacheValue;

/**
 * ArtifactorySCM plugin for Jenkins checkouts archive files from Artifactory and extracts to
 * Jenkins job workspace
 * 
 * Plugin
 * 
 * - checkouts artifactory file only when last modified date(last-modified header
 * returned when connecting to a URL) changes from last checkout date
 * 
 * - supports pooling using the same above logic
 * 
 * - supports extraction of zip,tar,gz,jar,war,ear files
 * 
 * - detects type of artifactory file based on file name (i.e URL must end with
 * zip,tar,tar.gz,jar,war,ear)
 * 
 * - supports basic authentication
 * 
 * - supports connection through proxy
 * 
 * - supports running on slave
 * 
 * - supports http:// and file:// protocols e.g - URL can be
 * 
 * http://www.apache.org/dyn/closer.cgi/maven/binaries/apache-maven-3.0.4-bin.
 * tar.gz file:///C:/Arjun/felix.jar (On Windows) file:///home/arjun/felix.jar
 * (On Unix/Linux)
 * 
 * Note: If the type is unknown the plugin will simply copy the file to
 * workspace
 */
@SuppressWarnings("restriction")
public class ArtifactorySCM extends SCM {

	/** The urls. */
	private final List<URLTuple> urls = new ArrayList<>();
	private String latestVersionTag;


	private String url;

	/** The clear workspace. */
	private boolean clearWorkspace;

	//private boolean useCache;

	private String credentialsId;


	/** The Constant LOGGER. */
	private static final Logger LOGGER = Logger.getLogger(ArtifactorySCM.class
			.getName());

	/**
	 * Instantiates a new Artifactory scm.
	 *
	 * @param url
	 *            the url - url
	 * @param clear
	 *            the clearWorkspace - clear workspace flag
	 *
	 * @param credentialsId
	 * 			  the
	 */
	@DataBoundConstructor
	public ArtifactorySCM(String url, String latestVersionTag, boolean clear, String credentialsId) {
		this.url = url;
		this.latestVersionTag = latestVersionTag;
		this.credentialsId = credentialsId;
		LOGGER.log(ALL, "ArtifactorySCM() Enter >>>");
		urls.add(new URLTuple(url, null, null));
		/*
		if (yourls != null) {
			for (int i = 0; i < yourls.length; i++) {
				urls.add(new URLTuple(yourls[i], username[i], password[i]));
			}
		}
		 */

		this.clearWorkspace = clear;
		//this.useCache = useCache;
		LOGGER.log(ALL, "ArtifactorySCM() Exit >>>");
	}

	/**
	 * Checks if is clear workspace.
	 * 
	 * @return true, if is clear workspace
	 */

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
	public void setLatestVersionTag(String latestVersionTag) { this.latestVersionTag = latestVersionTag; }

	@Exported
	public String getLatestVersionTag() {
		return latestVersionTag;
	}

	/**
	 * Gets the urls.
	 * 
	 * @return the urls
	 */
	public URLTuple[] getUrls() {
		return urls.toArray(new URLTuple[urls.size()]);
	}

    @Override
    public SCMRevisionState calcRevisionsFromBuild(
            @Nonnull final Run<?, ?> build, @Nullable final FilePath workspace,
            @Nullable final Launcher launcher, @Nonnull final TaskListener listener
    ) throws IOException, InterruptedException {
        // We add our SCMRevisionState from within checkout, so this shouldn't
        // be called often. However it will be called if this is the first
        // build, if a build was aborted before it reported the repository
        // state, etc.
        return SCMRevisionState.NONE;
    }

	/**
	 * This method downloads the file and also extracts it.
	 *
	 */
	@Override
	public void checkout(
			@Nonnull final Run<?, ?> run,
			@Nonnull Launcher launcher,
			@Nonnull FilePath workspace,
			@Nonnull TaskListener listener,
			File changelogFile,
			@CheckForNull final SCMRevisionState baseline) throws IOException, InterruptedException {
		LOGGER.log(ALL, "checkout() Enter >>>");

		if (clearWorkspace) {
			workspace.deleteContents();
			listener.getLogger().println("Cleared workspace");
		}
		long start = System.currentTimeMillis();
		LastModifiedDateAction action = new LastModifiedDateAction(run);
		/*
		Hudson h = Hudson.getInstance(); // this code might run on slaves
		ProxyConfiguration proxyConfiguration = h != null ? h.proxy : null;
		String proxyUserName = null;
		String proxyPassword = null;
		if (proxyConfiguration != null
				&& proxyConfiguration.getUserName() != null
				&& proxyConfiguration.getUserName().trim().length() > 0) {
			proxyUserName = proxyConfiguration.getUserName();
			proxyPassword = proxyConfiguration.getPassword();
		}
		 */

		for (URLTuple tuple : urls) {
			String urlString = tuple.getUrlString();
			InputStream is = null;
			try {
				URLConnection connection = null;
				listener.getLogger().println("File URL : " + urlString);
				listener.getLogger().println("Cred ID : " + getCredentialsId());

				UsernamePasswordCredentials passwordCredentials = initPasswordCredentials(run);
				if (passwordCredentials != null) {
					tuple.username = passwordCredentials.getUsername();
					tuple.password = passwordCredentials.getPassword().getPlainText();
					tuple.setAuthenticator();
				}

				String latestURLString = buildLatestURL(urlString, latestVersionTag, listener);
				listener.getLogger().println("Latest Version Available: " + latestURLString);
				URL url = new URL(latestURLString);
				connection = url.openConnection();

				/*
				if (proxyConfiguration == null) {
					listener.getLogger().println("Proxy is not configured");
					connection = url.openConnection();
				} else {
					listener.getLogger().println(
							"Proxy is configured"
									+ "\n"
									+ ProxyConfiguration.getXmlFile()
											.asString());
					// Proxy should be used when it is configured in Jenkins
					// global configuration
					connection = url.openConnection(proxyConfiguration
							.createProxy());
					// setting authentication parameters for proxy.
					// Authenticator class is not used here because of two
					// reasons. The actual URL to download file may be secured
					// and we can not set two Authenticators at
					// the same time using Authenticator.setDefault and the
					// second reason is username/password caching
					// bug in JDK.
					// (say if user gives incorrect username/password and when
					// validation is performed, that
					// username/password cached and even
					// if user gives correct password next time
					// java.net.URLConnection uses cached password only.
					// User name and password is not cached when it is set as
					// request parameter.)
					// http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=6626700
					if (proxyUserName != null) {
						listener.getLogger()
								.println(
										"User Name and Password is configured to connect through proxy");
						connection.setRequestProperty(
								"Proxy-Authorization",
								"Basic "
										+ new String(Base64
												.encodeBase64((proxyUserName
														+ ":" + proxyPassword)
														.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
					}

				}
				*/

				// Do not use cached file
				connection.setUseCaches(false);

				/*
				// A way to get headers from the artifactory request if needed
				String artifactVersion = connection.getHeaderField("X-Artifactory-Filename");
				listener.getLogger().println("Artifact File Version: " + artifactVersion);
				 */
				// Saving last modified time stamp for later use while polling
				// for source code change
				action.setLastModified(urlString, connection.getLastModified());
				long sourceLastUpdatedTimestamp = connection.getLastModified();

				File f = new File(url.getPath());
				String fileName = f.getName();
				// creating a timestamp file which will be used to see if source
				// file is updated since last download
				FilePath timestamp = workspace.child("." + fileName
						+ "-timestamp");

				if (!workspace.exists()) {
					workspace.mkdirs();
				}
				if (timestamp.exists()
						&& sourceLastUpdatedTimestamp == timestamp
								.lastModified()) {
					listener.getLogger().println("File is up to date");

				}
				/*
				else if (useCache) {
					listener.getLogger().println("Cache Option selected. Will not download source code from remote URL. Will attempt to run source locally");
				}
				*/
				else {
					// If clear workspace is left unchecked, a new artifact will not overwrite old files
					// With a new last modified data aka a new artifact found, we should delete the workspace
					workspace.deleteContents();
					listener.getLogger().println("Clearing workspace for new files");

					// for HTTP downloads, enable automatic retry for added
					// resilience
					is = new CountingInputStream(url.getProtocol().equals(
							"http") ? new RetryableHttpStream(url)
							: connection.getInputStream());

					if (url.toExternalForm().endsWith(".zip")
							|| url.toExternalForm().endsWith(".jar")
							|| url.toExternalForm().endsWith(".war")
							|| url.toExternalForm().endsWith(".ear")) {
						listener.getLogger().println(
								"Compression type is zip/jar/war");
						workspace.unzipFrom(is);
					} else if (url.toExternalForm().endsWith(".gz")) {
						listener.getLogger().println("Compression type is gz");
						workspace.untarFrom(is, GZIP);
					} else if (url.toExternalForm().endsWith(".tar")) {
						listener.getLogger().println("Compression type is tar");
						workspace.untarFrom(is, NONE);
					} else {
						listener.getLogger()
								.println(
										"Compression type unknown. Hence directly downloading the file");
						listener.getLogger().println(workspace.child(fileName).getName());
						workspace.child(fileName).copyFrom(is);
					}

					// If zip is packaged with a parent directory, move children up to workspace and remove directory
					if (workspace.list().size() == 1 && workspace.list().get(0).isDirectory())
						workspace.list().get(0).moveAllChildrenTo(workspace);

					listener.getLogger().println(
							"Downloaded " + latestURLString + " to "
									+ workspace.toURI());
					// update the last modified timestamp of timestamp file
					timestamp.touch(sourceLastUpdatedTimestamp);
				}
			} catch (RuntimeException e) {
				throw new RuntimeException("Couldn't process stuff", e);
			} catch (Exception e) {
				listener.error("Unable to copy " + urlString + "\n");
				e.printStackTrace(listener.getLogger());
				LOGGER.log(ALL, " checkout() Exit >>>");
				return;
			} finally {
				if (is != null)
					is.close();
			}

			this.createEmptyChangeLog(changelogFile, listener, "log");
		}
		// Adding LastModificationDateAction to build for later use while
		// pooling. This is optimized code as
		// calcRevisionsFromBuild method will not be invoked
		run.addAction(action);
		listener.getLogger().println(
				"Total time taken to download files in millis: "
						+ (System.currentTimeMillis() - start));

		LOGGER.log(ALL, " checkout() Exit >>>");
	}

	UsernamePasswordCredentials initPasswordCredentials(Run<?, ?> run) {
		final UsernamePasswordCredentials passwordCredentials;
		StandardUsernameCredentials credentials = findCredentialById(credentialsId, StandardUsernameCredentials.class, run);
		if (credentials instanceof UsernamePasswordCredentials) {
			passwordCredentials = (UsernamePasswordCredentials) credentials;
		} else {
			passwordCredentials = null;
		}
		return passwordCredentials;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see hudson.scm.SCM#createChangeLogParser()
	 */
	@Override
	public ChangeLogParser createChangeLogParser() {
		return new NullChangeLogParser();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see hudson.scm.SCM#requiresWorkspaceForPolling()
	 */
	@Override
	public boolean requiresWorkspaceForPolling() {
		// workspace is not used for pooling
		return false;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * hudson.scm.SCM#compareRemoteRevisionWith(hudson.model.AbstractProject,
	 * hudson.Launcher, hudson.FilePath, hudson.model.TaskListener,
	 * hudson.scm.SCMRevisionState)
	 */
	@Override
	public PollingResult compareRemoteRevisionWith(
			Job<?, ?> project, Launcher launcher,
			FilePath workspace, TaskListener listener, SCMRevisionState baseline)
			throws IOException, InterruptedException {
		LOGGER.log(ALL, "compareRemoteRevisionWith() Enter >>>");
		PollingResult pollingResult = PollingResult.NO_CHANGES;

		Run<?, ?> lastBuild = project.getLastBuild();
		// When no previous build exits and this is the first build we got to
		// build
		if (lastBuild != null) {
			listener.getLogger().println(
					"[poll] Last Build : #" + lastBuild.getNumber());
		} else {
			listener.getLogger()
					.println(
							"[poll] No previous build, so forcing an initial build.\ncompareRemoteRevisionWith() Exit >>>");
			LOGGER.log(ALL, "compareRemoteRevisionWith() Exit >>>");
			return PollingResult.BUILD_NOW;
		}
		// Rebuild if working directory does not exits
		if (workspace != null && !workspace.exists()) {
			listener.getLogger()
					.println(
							"Rebuilding as working directory doesnot exits.\ncompareRemoteRevisionWith() Exit >>>");
			LOGGER.log(ALL, "compareRemoteRevisionWith() Exit >>>");
			return PollingResult.BUILD_NOW;
		}
		LastModifiedDateAction action = lastBuild
				.getAction(LastModifiedDateAction.class);
		if (action == null) {
			listener.getLogger()
					.println(
							"There are significant changes.\ncompareRemoteRevisionWith() Exit >>>");
			LOGGER.log(ALL, "compareRemoteRevisionWith() Exit >>>");
			return PollingResult.SIGNIFICANT;
		}
		for (URLTuple tuple : urls) {
			String urlString = tuple.getUrlString();
			try {
				URL url = new URL(urlString);
				URLConnection conn = url.openConnection();
				conn.setUseCaches(false);
				long lastMod = conn.getLastModified();
				long lastBuildMod = action.getLastModified(urlString);
				if (lastBuildMod != lastMod) {
					listener.getLogger().println(
							"Found change: " + urlString + " modified "
									+ new Date(lastMod)
									+ " previous modification was "
									+ new Date(lastBuildMod));
					pollingResult = PollingResult.SIGNIFICANT;
					break;
				}
			} catch (Exception e) {
				listener.error("Unable to check " + urlString + "\n"
						+ e.getMessage());
				e.printStackTrace(listener.getLogger());
			}
		}
		LOGGER.log(ALL, "compareRemoteRevisionWith() Exit >>>");
		return pollingResult;
	}

	/**
	 * The Class ArtifactorySCMDescriptorImpl.
	 */
	@Extension
	public static final class ArtifactorySCMDescriptorImpl extends
			SCMDescriptor<ArtifactorySCM> {

		/**
		 * Instantiates a new artifactory scm descriptor impl.
		 */
		public ArtifactorySCMDescriptorImpl() {
			super(ArtifactorySCM.class, null);
			load();
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see hudson.model.Descriptor#getDisplayName()
		 */
		public String getDisplayName() {
			return "Artifactory SCM";
		}

		// This NEEDS to be overriden! Not having this causes the scm option to not show up in the configuration page!
		@Override
		public boolean isApplicable(Job project) {
			return true;
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see
		 * hudson.model.Descriptor#configure(org.kohsuke.stapler.StaplerRequest,
		 * net.sf.json.JSONObject)
		 */
		@Override
		public boolean configure(StaplerRequest req, JSONObject formData)
				throws FormException {
			return true;
		}

		/**
		 * Do required check.
		 * 
		 * @param value
		 *            the value
		 * @return the form validation
		 * @throws ServletException
		 *             the servlet exception
		 */
		public FormValidation doRequiredCheck(@QueryParameter final String value)
				throws ServletException {
			LOGGER.log(ALL, "doRequiredCheck() Enter >>>");
			LOGGER.log(INFO, "value :" + value);
			LOGGER.log(ALL, "doRequiredCheck() Exit >>>");
			return FormValidation.validateRequired(value);
		}

		public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item item,
													 @QueryParameter String credentialsId) {
			StandardListBoxModel result = new StandardListBoxModel();
			result.includeEmptyValue();

			if (item == null) {
				if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
					return result.includeCurrentValue(credentialsId);
				}
			} else {
				if (!item.hasPermission(Item.EXTENDED_READ) && !item.hasPermission(USE_ITEM)) {
					return result.includeCurrentValue(credentialsId);
				}
			}
			List<StandardUsernameCredentials> standardUsernameCredentials = lookupCredentials(
					StandardUsernameCredentials.class, item, ACL.SYSTEM, Collections.emptyList());
			for (StandardUsernameCredentials standardUsernameCredential : standardUsernameCredentials) {
				result.with(standardUsernameCredential);
			}
			return result;
		}


	}

	/**
	 * Function to build the URL using the latestVersion Tag
	 * @return
	 * @param urlString
	 * @param latestVersionTag represents the git branch of the code the artifact represents
	 */


	public String buildLatestURL(String urlString, String latestVersionTag, TaskListener listener) throws IOException {
		// URL should be the folder, need to reach artifactory API to get request
		String apiBase = "api/search/aql";
		String noHttpsURL = urlString.replace("https://", "");
		String branch = "";
		// index 0 = artifactory endpoint
		// index 1 = artifactory
		// index 2 = repo
		// index 3 = repo folder, unless using legacy method. should be last index of split url
		String[] url = noHttpsURL.split("/");
		String repoFolder = url[url.length-1];

		String apiRequest = String.format("https://%s/%s/%s", url[0], url[1], apiBase);
		String payload = "";

		// Supports legacy way of grabbing without version tag
		if (latestVersionTag.isEmpty()) {
			payload = String.format("items.find( { \"repo\":\"%s\", \"name\":{\"$match\" : \"%s-%s*.zip\" } }).sort({\"$desc\":[\"modified\"]}).limit(1)", url[2], repoFolder, branch);
		}
		// Gets rid of sort because we can sort ourselves with the build number
		else if (latestVersionTag.length() > 0) {
			branch = latestVersionTag + '*';
			payload = String.format("items.find( { \"repo\":\"%s\", \"name\":{\"$match\" : \"%s-%s.zip\" } })", url[2], repoFolder, branch);
		}

		URL full_url = new URL(apiRequest);
		HttpURLConnection connection = (HttpURLConnection) full_url.openConnection();

		// Set the appropriate HTTP method and headers for a POST request
		connection.setRequestMethod("POST");
		connection.setRequestProperty("Content-Type", "text/plain");
		connection.setDoOutput(true);

		// Write the payload to the connection's output stream
		try (OutputStream outputStream = connection.getOutputStream()) {
			byte[] input = payload.getBytes(StandardCharsets.UTF_8);
			outputStream.write(input);
		}

		// Read the response of the API call and grab repo, path, and name
		// Using Jackson parser to create JSON Map object for "easy" object grabbing
        InputStream inputStream = connection.getInputStream();
		ObjectMapper mapper = new ObjectMapper();
		Map<String, Object> jsonMap = mapper.readValue(inputStream, Map.class);
        List<Map<String, Object>> results = (List<Map<String, Object>>) jsonMap.get("results");
		Map<String, Object> finalResult = null;

		// Sort the results by the number at the end of the "name" string
		if (latestVersionTag.length() > 0) {
			List<Map<String, Object>> sortedResults = results.stream()
					.sorted(Comparator.comparingInt(result -> extractNumberFromName((String) result.get("name"))))
					.collect(Collectors.toList());
			finalResult = sortedResults.get(sortedResults.size()-1);
		}
		// Legacy method, just get first from AQL query's list
		else {
			finalResult = results.get(0);
		}

		String repo = (String) finalResult.get("repo");
		String path = (String) finalResult.get("path");
		String name = (String) finalResult.get("name");

		/*
		// Remove these potential strings from the tag
		String[] removeables = {".zip",  ".signedmanifest", ".smf", ".sig"};
		for (int i = 0; i < removeables.length; i++) {
			name = name.replace(removeables[i], "");
		}
		 */
		String latestURL = String.format("https://%s/%s/%s/%s/%s", url[0], url[1], repo, path, name);
		return latestURL;
	}

	// Used to help sort the list of artifactory results by build number
	private static int extractNumberFromName(String name) {
		String[] parts = name.split("-");
		String numberPart = parts[parts.length - 1].replace(".zip", "");
		return Integer.parseInt(numberPart);
	}

	/**
	 * The Class URLTuple.
	 */
	public static final class URLTuple {

		/** The url string. */
		private final String urlString;

		/** The username. */
		private String username;

		/** The password. */
		private String password;

		/**
		 * Instantiates a new uRL tuple.
		 * 
		 * @param urlString
		 *            the url string
		 * @param username
		 *            the username
		 * @param password
		 *            the password
		 */
		public URLTuple(String urlString, String username, String password) {
			LOGGER.log(ALL, "URLTuple() Enter >>>");
			this.urlString = urlString;
			// In the url is not secured initialize user name and password with
			// empty strings
			if (username == null || username.trim().length() == 0) {
				this.username = "";
				this.password = "";
			} else {
				this.username = username;
				this.password = password;
				this.setAuthenticator();
			}
			LOGGER.log(ALL, "URLTuple() Exit >>>");
		}

		/**
		 * Gets the url string.
		 * 
		 * @return the url string
		 */
		public String getUrlString() {
			return urlString;
		}

		/**
		 * Gets the username.
		 * 
		 * @return the username
		 */
		public String getUsername() {
			return username;
		}

		/**
		 * Gets the password.
		 * 
		 * @return the password
		 */
		public String getPassword() {
			return password;
		}

		/**
		 * The Class SecuredResourceAuthenticator.
		 */
		private class SecuredResourceAuthenticator extends Authenticator {

			/*
			 * (non-Javadoc)
			 * 
			 * @see java.net.Authenticator#getPasswordAuthentication()
			 */
			public PasswordAuthentication getPasswordAuthentication() {
				return new PasswordAuthentication(username,
						password.toCharArray());
			}
		}

		/**
		 * Sets the authenticator.
		 */
		public void setAuthenticator() {
			// clearing cache - we require to clear cache because of JDK bug
			// AuthCacheValue.setAuthCache(new AuthCacheImpl());
			Authenticator.setDefault(new SecuredResourceAuthenticator());
		}
	}
}
