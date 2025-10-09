package com.mwdle.bitwarden;

import com.cloudbees.plugins.credentials.CredentialsMatcher;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.mwdle.bitwarden.cli.BitwardenCLI;
import com.mwdle.bitwarden.cli.BitwardenCLIManager;
import com.mwdle.bitwarden.cli.BitwardenSessionManager;
import com.mwdle.bitwarden.converters.CredentialProxy;
import hudson.Extension;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jakarta.annotation.Nonnull;
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
 * Manages the system-wide configuration for the Bitwarden Credentials Provider plugin.
 * <p>
 * This class is a singleton managed by Jenkins, responsible for storing the plugin's global
 * settings, presenting them in the "Configure System" UI, and handling the logic when the
 * configuration is saved by a user or by JCasC.
 */
@Extension
@Symbol("bitwarden")
public class BitwardenConfig extends GlobalConfiguration {

    private static final Logger LOGGER = Logger.getLogger(BitwardenConfig.class.getName());

    /**
     * A transient, in-memory snapshot of the configuration as it was last loaded or saved.
     * This is used to determine if critical settings have changed,
     * preventing unnecessary re-authentication and cache refreshes on every save.
     */
    private transient BitwardenConfig loadedConfig;

    /** The URL of the self-hosted Bitwarden/Vaultwarden server. */
    private String serverUrl;
    /** The Jenkins credential ID for the Bitwarden API Key (Client ID & Secret). */
    private String apiCredentialId;
    /** The Jenkins credential ID for the Bitwarden Master Password. */
    private String masterPasswordCredentialId;
    /** The absolute path to a manually installed Bitwarden CLI executable. */
    private String cliExecutablePath;
    /** The cache duration in minutes for the list of item metadata. */
    private int cacheDuration = 5; // Default to 5 minutes
    /** A comma-separated list of suffixes to identify FileCredentials. */
    // This field stores non-secret configuration strings, not secrets.
    // lgtm[jenkins/plaintext-storage]
    private String fileCredentialSuffixes;

    /**
     * Called by Jenkins at startup to create the singleton instance of this class.
     * <p>
     * The constructor first calls {@link #load()} to populate the fields from the persisted XML
     * configuration on disk.
     */
    public BitwardenConfig() {
        load();
        LOGGER.fine("BitwardenConfig loaded from disk.");
    }

    /**
     * Provides the display name for this configuration section in the "Configure System" page.
     *
     * @return The internationalized display name.
     */
    @Override
    @Nonnull
    public String getDisplayName() {
        return Messages.BitwardenConfig_DisplayName();
    }

    /**
     * Provides global access to the single instance of this configuration.
     *
     * @return The singleton instance of {@link BitwardenConfig}.
     */
    public static BitwardenConfig getInstance() {
        return GlobalConfiguration.all().get(BitwardenConfig.class);
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public String getApiCredentialId() {
        return apiCredentialId;
    }

    public String getMasterPasswordCredentialId() {
        return masterPasswordCredentialId;
    }

    public String getCliExecutablePath() {
        return cliExecutablePath;
    }

    public int getCacheDuration() {
        return cacheDuration;
    }

    public String getFileCredentialSuffixes() {
        return fileCredentialSuffixes;
    }

    @DataBoundSetter
    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    @DataBoundSetter
    public void setApiCredentialId(String apiCredentialId) {
        this.apiCredentialId = apiCredentialId;
    }

    @DataBoundSetter
    public void setMasterPasswordCredentialId(String masterPasswordCredentialId) {
        this.masterPasswordCredentialId = masterPasswordCredentialId;
    }

    @DataBoundSetter
    public void setCliExecutablePath(String cliExecutablePath) {
        this.cliExecutablePath = cliExecutablePath;
    }

    @DataBoundSetter
    public void setCacheDuration(int cacheDuration) {
        this.cacheDuration = (cacheDuration > 0) ? cacheDuration : 5;
    }

    @DataBoundSetter
    public void setFileCredentialSuffixes(String fileCredentialSuffixes) {
        this.fileCredentialSuffixes = fileCredentialSuffixes;
    }

    /**
     * Creates a simple, in-memory copy of this object's critical settings for state comparison.
     *
     * @return A new {@link BitwardenConfig} instance with copied fields.
     */
    private BitwardenConfig snapshot() {
        BitwardenConfig snapshot = new BitwardenConfig();
        snapshot.serverUrl = this.serverUrl;
        snapshot.apiCredentialId = this.apiCredentialId;
        snapshot.masterPasswordCredentialId = this.masterPasswordCredentialId;
        snapshot.cliExecutablePath = this.cliExecutablePath;
        return snapshot;
    }

    /**
     * A helper method to check if the essential configuration (API key and master password) is present.
     *
     * @return {@code true} if the plugin is configured with the minimum required credentials.
     */
    public boolean isConfigured() {
        return apiCredentialId != null
                && !apiCredentialId.isEmpty()
                && masterPasswordCredentialId != null
                && !masterPasswordCredentialId.isEmpty();
    }

    /**
     * The entry point for Jenkins when a user saves the global configuration from the UI.
     * It binds the form data to this object's fields and then calls {@link #save()}.
     * <p>
     * By calling {@link #save()}, we create a single, unified hook that ensures changes made
     * by both users (via this method) and by JCasC are handled consistently.
     *
     * @param req  The current web request.
     * @param json The JSON object representing the form data for this configuration section.
     * @return {@code true} to indicate success.
     * @throws FormException if the form data cannot be processed.
     */
    @Override
    public boolean configure(StaplerRequest2 req, JSONObject json) throws FormException {
        super.configure(req, json);
        save();
        return true;
    }

    /**
     * The unified hook for all configuration changes, called by both the UI (via {@link #configure}) and JCasC.
     * <p>
     * This method performs a "dirty check" to see if any critical settings have actually changed.
     * If they have, it triggers a background task to re-authenticate and refresh the credential cache.
     */
    @Override
    public void save() {
        super.save();
        boolean configChanged = loadedConfig == null
                || !Objects.equals(this.serverUrl, loadedConfig.serverUrl)
                || !Objects.equals(this.apiCredentialId, loadedConfig.apiCredentialId)
                || !Objects.equals(this.masterPasswordCredentialId, loadedConfig.masterPasswordCredentialId)
                || !Objects.equals(this.cliExecutablePath, loadedConfig.cliExecutablePath);

        if (isConfigured() && configChanged) {
            LOGGER.info("Bitwarden configuration has changed, triggering background re-authentication and sync.");
            Timer.get().submit(() -> {
                BitwardenSessionManager.getInstance().invalidateSessionToken();
                BitwardenCacheManager.getInstance().invalidateCache();
                if (BitwardenCLIManager.getInstance().provisionExecutable()) {
                    BitwardenCacheManager.getInstance().updateCache();
                }
            });
        }
        // After any save, update our snapshot to the new state.
        this.loadedConfig = snapshot();
    }

    /**
     * Creates a credentials matcher for the configuration dropdowns.
     * This filter includes standard credentials but excludes any credentials that come from
     * this plugin itself, preventing a "chicken-and-egg" problem.
     *
     * @return A {@link CredentialsMatcher} to filter the list of available credentials.
     */
    private CredentialsMatcher getCredentialsMatcher() {
        return CredentialsMatchers.allOf(
                CredentialsMatchers.anyOf(
                        CredentialsMatchers.withScope(CredentialsScope.SYSTEM),
                        CredentialsMatchers.withScope(CredentialsScope.GLOBAL)),
                credential -> !(Proxy.isProxyClass(credential.getClass())
                        && Proxy.getInvocationHandler(credential) instanceof CredentialProxy));
    }

    /**
     * Populates the "Bitwarden API Key Credential" dropdown in the UI.
     * <p>
     * This method is called by Stapler.
     *
     * @param context The current Jenkins context.
     * @param apiCredentialId The ID of the currently selected credential.
     * @return A {@link ListBoxModel} containing suitable credentials.
     */
    @POST
    public ListBoxModel doFillApiCredentialIdItems(
            @AncestorInPath Jenkins context, @QueryParameter String apiCredentialId) {
        if (!context.hasPermission(Jenkins.MANAGE))
            return new StandardListBoxModel().includeCurrentValue(apiCredentialId);
        return new StandardListBoxModel()
                .includeEmptyValue()
                .includeMatchingAs(
                        ACL.SYSTEM2,
                        context.getItemGroup(),
                        StandardUsernamePasswordCredentials.class,
                        Collections.emptyList(),
                        getCredentialsMatcher())
                .includeCurrentValue(apiCredentialId);
    }

    /**
     * Populates the "Bitwarden Master Password Credential" dropdown in the UI.
     * <p>
     * This method is called by Stapler.
     *
     * @param context The current Jenkins context.
     * @param masterPasswordCredentialId The ID of the currently selected credential.
     * @return A {@link ListBoxModel} containing suitable credentials.
     */
    @POST
    public ListBoxModel doFillMasterPasswordCredentialIdItems(
            @AncestorInPath Jenkins context, @QueryParameter String masterPasswordCredentialId) {
        if (!context.hasPermission(Jenkins.MANAGE))
            return new StandardListBoxModel().includeCurrentValue(masterPasswordCredentialId);
        return new StandardListBoxModel()
                .includeEmptyValue()
                .includeMatchingAs(
                        ACL.SYSTEM2,
                        context.getItemGroup(),
                        StringCredentials.class,
                        Collections.emptyList(),
                        getCredentialsMatcher())
                .includeCurrentValue(masterPasswordCredentialId);
    }

    /**
     * An action method for the "Refresh Now" button in the UI.
     * <p>
     * This method is called by Stapler.
     * <p>
     * Forces a re-authentication and triggers a non-destructive background refresh of the cache.
     *
     * @return A {@link FormValidation} object indicating the action has started.
     */
    @POST
    public FormValidation doRefreshCache() {
        Jenkins.get().checkPermission(Jenkins.MANAGE);
        try {
            LOGGER.info("Manual cache refresh triggered by administrator.");
            BitwardenSessionManager.getInstance().invalidateSessionToken();
            BitwardenCacheManager.getInstance().updateCache();
            return FormValidation.ok(Messages.validation_refreshStarted());
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to start manual cache refresh", e);
            return FormValidation.error(Messages.validation_refreshError(e.getMessage()));
        }
    }

    /**
     * An action method for the "Check Version" button in the UI.
     * <p>
     * This method is called by Stapler.
     *
     * @return A {@link FormValidation} object showing the installed CLI version or an error.
     */
    @POST
    public FormValidation doCheckCliVersion() {
        Jenkins.get().checkPermission(Jenkins.MANAGE);
        try {
            String currentVersion = BitwardenCLI.version();
            return FormValidation.ok(Messages.validation_cliVersion(currentVersion));
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to check Bitwarden CLI version", e);
            return FormValidation.error(Messages.validation_cliError(e.getMessage()));
        }
    }

    /**
     * An action method for the "Download Latest" button in the UI.
     * <p>
     * This method is called by Stapler.
     *
     * @return A {@link FormValidation} object indicating the result of the download attempt.
     */
    @POST
    public FormValidation doForceUpdateCli() {
        Jenkins.get().checkPermission(Jenkins.MANAGE);
        String userPath = getCliExecutablePath();
        if (userPath != null && !userPath.trim().isEmpty()) {
            return FormValidation.warning(Messages.validation_cliUpdateManual());
        }
        try {
            LOGGER.info("Manual Bitwarden CLI update triggered by administrator.");
            BitwardenCLIManager.getInstance().downloadLatestExecutable();
            String newVersion = BitwardenCLI.version();
            return FormValidation.ok(Messages.validation_cliUpdateOk(newVersion));
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Manual CLI update failed", e);
            return FormValidation.error(Messages.validation_cliUpdateError(e.getMessage()));
        }
    }

    /**
     * An action method for the "Verify Session" button in the UI.
     * <p>
     * This method is called by Stapler.
     * It performs a fast, read-only check to see if the {@link com.mwdle.bitwarden.cli.BitwardenSessionManager}
     * currently holds a valid, unlocked session token.
     *
     * @return A {@link FormValidation} object indicating if the current session is active or not.
     */
    @POST
    public FormValidation doVerifySession() {
        Jenkins.get().checkPermission(Jenkins.MANAGE);
        if (!isConfigured()) {
            return FormValidation.warning(Messages.validation_sessionNotConfigured());
        }
        boolean isValid = BitwardenSessionManager.getInstance().isSessionValid();
        if (isValid) {
            return FormValidation.ok(Messages.validation_sessionOk());
        } else {
            return FormValidation.warning(Messages.validation_sessionNotFound());
        }
    }
}
