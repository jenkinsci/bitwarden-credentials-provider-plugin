package com.mwdle.bitwarden;

import static com.mwdle.bitwarden.util.StringUtils.stripToNull;

import com.cloudbees.plugins.credentials.CredentialsMatcher;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.mwdle.bitwarden.cli.BitwardenCli;
import com.mwdle.bitwarden.cli.BitwardenCliManager;
import com.mwdle.bitwarden.cli.SessionManager;
import com.mwdle.bitwarden.converters.CredentialProxy;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.Util;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import jenkins.util.Timer;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.verb.POST;

/**
 * A Jenkins-managed singleton responsible for managing this plugin.
 */
@Extension
@Symbol("bitwarden")
public final class BitwardenConfig extends GlobalConfiguration {

    private static final Logger LOGGER = Logger.getLogger(BitwardenConfig.class.getName());

    /** A matcher including SYSTEM and GLOBAL credentials, excluding those created by this plugin to prevent a circular dependency. */
    private static final CredentialsMatcher CREDENTIALS_MATCHER = CredentialsMatchers.allOf(
            CredentialsMatchers.anyOf(
                    CredentialsMatchers.withScope(CredentialsScope.SYSTEM),
                    CredentialsMatchers.withScope(CredentialsScope.GLOBAL)),
            credential -> !(Proxy.isProxyClass(credential.getClass())
                    && Proxy.getInvocationHandler(credential) instanceof CredentialProxy));

    /** The default cache duration in minutes. */
    private static final String DEFAULT_SERVER_URL = "https://vault.bitwarden.com";
    /** The default cache duration in minutes. */
    private static final int DEFAULT_CACHE_DURATION = 5;

    /** The URL of the Bitwarden server. */
    private String serverUrl = DEFAULT_SERVER_URL;
    /** The Jenkins credential ID for the Bitwarden API Key. */
    private String apiCredentialId;
    /** The Jenkins credential ID for the Bitwarden Master Password. */
    private String masterPasswordCredentialId;
    /** The absolute path to a manually installed Bitwarden CLI executable. */
    private String cliExecutablePath;
    /** The cache duration in minutes for the list of Bitwarden item metadata. */
    private int cacheDuration = DEFAULT_CACHE_DURATION;
    /** A comma-separated list of suffixes to identify if a Bitwarden item should be treated as a File credential based on its name. */
    @SuppressWarnings("lgtm[jenkins/plaintext-storage]")
    private String fileCredentialSuffixes;

    /** Determines if primary settings (server URL, core credentials, CLI path) changed and require Bitwarden reauthentication and sync. */
    @SuppressWarnings("squid:S2065")
    private transient boolean requiresReauthentication = false;
    /** Determines if file credential suffixes changed and require a Bitwarden sync. */
    @SuppressWarnings("squid:S2065")
    private transient boolean requiresCacheRefresh = false;

    public BitwardenConfig() {
        load();
    }

    /**
     * @return The singleton instance of this manager.
     */
    @NonNull
    public static BitwardenConfig getInstance() {
        return ExtensionList.lookupSingleton(BitwardenConfig.class);
    }

    /**
     * @return {@code true} if the plugin is configured with an API key and master password credential ID
     */
    public boolean isConfigured() {
        return apiCredentialId != null && masterPasswordCredentialId != null;
    }

    @Override
    @NonNull
    public String getDisplayName() {
        return Messages.BitwardenConfig_DisplayName();
    }

    @NonNull
    public String getServerUrl() {
        return Util.fixNull(serverUrl, DEFAULT_SERVER_URL);
    }

    @DataBoundSetter
    public void setServerUrl(@CheckForNull String serverUrl) {
        String strippedUrl = stripToNull(serverUrl);
        if (!Objects.equals(this.serverUrl, strippedUrl)) {
            requiresReauthentication = true;
        }
        this.serverUrl = strippedUrl;
    }

    @CheckForNull
    public String getApiCredentialId() {
        return apiCredentialId;
    }

    @DataBoundSetter
    public void setApiCredentialId(@CheckForNull String apiCredentialId) {
        String strippedId = stripToNull(apiCredentialId);
        if (!Objects.equals(this.apiCredentialId, strippedId)) {
            requiresReauthentication = true;
        }
        this.apiCredentialId = strippedId;
    }

    @CheckForNull
    public String getMasterPasswordCredentialId() {
        return masterPasswordCredentialId;
    }

    @DataBoundSetter
    public void setMasterPasswordCredentialId(@CheckForNull String masterPasswordCredentialId) {
        String strippedId = stripToNull(masterPasswordCredentialId);
        if (!Objects.equals(this.masterPasswordCredentialId, strippedId)) {
            requiresReauthentication = true;
        }
        this.masterPasswordCredentialId = strippedId;
    }

    @CheckForNull
    public String getFileCredentialSuffixes() {
        return fileCredentialSuffixes;
    }

    @DataBoundSetter
    public void setFileCredentialSuffixes(@CheckForNull String fileCredentialSuffixes) {
        String strippedSuffixes = stripToNull(fileCredentialSuffixes);
        if (!Objects.equals(this.fileCredentialSuffixes, strippedSuffixes)) {
            requiresCacheRefresh = true;
        }
        this.fileCredentialSuffixes = strippedSuffixes;
    }

    @CheckForNull
    public String getCliExecutablePath() {
        return cliExecutablePath;
    }

    @DataBoundSetter
    public void setCliExecutablePath(@CheckForNull String cliExecutablePath) {
        String strippedPath = stripToNull(cliExecutablePath);
        if (!Objects.equals(this.cliExecutablePath, strippedPath)) {
            requiresReauthentication = true;
        }
        this.cliExecutablePath = strippedPath;
    }

    public int getCacheDuration() {
        return (cacheDuration > 0) ? cacheDuration : DEFAULT_CACHE_DURATION;
    }

    @DataBoundSetter
    public void setCacheDuration(int duration) {
        int newDuration = (duration > 0) ? duration : DEFAULT_CACHE_DURATION;
        if (this.cacheDuration != newDuration) {
            requiresCacheRefresh = true;
        }
        this.cacheDuration = newDuration;
    }

    @Override
    public boolean configure(@NonNull StaplerRequest2 req, @NonNull JSONObject json) throws FormException {
        super.configure(req, json);
        save();
        return true;
    }

    /**
     * Triggers a background task to re-authenticate and/or resync the credential cache if any critical settings have changed.
     * <p>
     * {@inheritDoc}
     */
    @Override
    public synchronized void save() {
        super.save();
        if (requiresReauthentication) {
            this.requiresReauthentication = false;
            this.requiresCacheRefresh = false;
            LOGGER.info("Bitwarden primary configuration settings updated.");
            Timer.get().submit(() -> {
                SessionManager.getInstance().invalidateSession();
                CacheManager.getInstance().invalidateCache();
                if (isConfigured() && BitwardenCliManager.getInstance().provisionExecutable()) {
                    CacheManager.getInstance().refreshCache();
                }
            });
        } else if (requiresCacheRefresh) {
            this.requiresCacheRefresh = false;
            LOGGER.info("Bitwarden item processing configuration settings updated.");
            Timer.get().submit(() -> {
                CacheManager.getInstance().invalidateCache();
                if (isConfigured()) {
                    CacheManager.getInstance().refreshCache();
                }
            });
        }
    }

    /**
     * Populates the Bitwarden API Key Credential dropdown in the UI.
     *
     * @param context the current Jenkins context
     * @param credentialId the ID of the currently selected credential
     * @return a list of suitable credentials
     */
    @POST
    @NonNull
    public ListBoxModel doFillApiCredentialIdItems(
            @NonNull @AncestorInPath Jenkins context, @CheckForNull @QueryParameter String credentialId) {
        return createCredentialsListBox(credentialId, context, StandardUsernamePasswordCredentials.class);
    }

    /**
     * Populates the Bitwarden Master Password Credential dropdown in the UI.
     *
     * @param context the current Jenkins context
     * @param credentialId the ID of the currently selected credential
     * @return a list of suitable credentials
     */
    @POST
    @NonNull
    public ListBoxModel doFillMasterPasswordCredentialIdItems(
            @NonNull @AncestorInPath Jenkins context, @CheckForNull @QueryParameter String credentialId) {
        return createCredentialsListBox(credentialId, context, StringCredentials.class);
    }

    /**
     * Returns a dropdown list of credentials, applying security checks and filtering by type.
     *
     * @param id the ID of the currently selected credential
     * @param context the Jenkins context
     * @param credentialClass the Class of the concrete credential type to find
     * @return a populated list box model for the Jenkins UI
     */
    @NonNull
    private static ListBoxModel createCredentialsListBox(
            @CheckForNull String id,
            @NonNull Jenkins context,
            @NonNull Class<? extends StandardCredentials> credentialClass) {
        id = Util.fixNull(id);
        return !context.hasPermission(Jenkins.ADMINISTER)
                ? new StandardListBoxModel().includeEmptyValue()
                : new StandardListBoxModel()
                        .includeEmptyValue()
                        .includeMatchingAs(
                                ACL.SYSTEM2,
                                context.getItemGroup(),
                                credentialClass,
                                Collections.emptyList(),
                                CREDENTIALS_MATCHER)
                        .includeCurrentValue(id);
    }

    /**
     * An action for the "Refresh Now" button in the UI.
     * <p>
     * Forces background Bitwarden reauthentication and sync.
     *
     * @return a form validation indicating the action was triggered
     */
    @POST
    @NonNull
    public FormValidation doRefreshCache() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        LOGGER.info("Manual cache refresh triggered by administrator");
        SessionManager.getInstance().invalidateSession();
        CacheManager.getInstance().refreshCache();
        return FormValidation.ok(Messages.validation_refreshStarted());
    }

    /**
     * An action for the "Check Version" button in the UI.
     *
     * @return a form validation showing the installed Bitwarden CLI version or an error
     */
    @POST
    @NonNull
    public FormValidation doCheckCliVersion() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        try {
            return FormValidation.ok(Messages.validation_cliVersion(BitwardenCli.version()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.log(Level.WARNING, "Failed to check Bitwarden CLI version", e);
            return FormValidation.error(Messages.validation_cliError(e.getMessage()));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to check Bitwarden CLI version", e);
            return FormValidation.error(Messages.validation_cliError(e.getMessage()));
        }
    }

    /**
     * An action for the "Download Latest" button in the UI.
     *
     * @return a form validation indicating the result of the download/update attempt
     */
    @POST
    @NonNull
    public FormValidation doForceUpdateCli() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if (getCliExecutablePath() != null) {
            return FormValidation.warning(Messages.validation_cliUpdateManual());
        }
        LOGGER.info("Bitwarden CLI update triggered by administrator");
        BitwardenCliManager.getInstance().downloadLatestExecutable();
        try {
            return FormValidation.ok(Messages.validation_cliUpdateOk(BitwardenCli.version()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.log(Level.WARNING, "Manual CLI update failed", e);
            return FormValidation.error(Messages.validation_cliUpdateError(e.getMessage()));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Manual CLI update failed", e);
            return FormValidation.error(Messages.validation_cliUpdateError(e.getMessage()));
        }
    }

    /**
     * An action for the "Verify Session" button in the UI.
     *
     * @return a form validation indicating whether the plugin has an active Bitwarden CLI session
     */
    @POST
    @NonNull
    public FormValidation doVerifySession() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if (!isConfigured()) {
            return FormValidation.warning(Messages.validation_sessionNotConfigured());
        }
        boolean isValid = SessionManager.getInstance().isSessionValid();
        if (isValid) {
            return FormValidation.ok(Messages.validation_sessionOk());
        } else {
            return FormValidation.warning(Messages.validation_sessionNotFound());
        }
    }
}
