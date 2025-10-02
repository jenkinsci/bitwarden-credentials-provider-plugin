package com.mwdle;

import com.cloudbees.plugins.credentials.CredentialsMatcher;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.mwdle.bitwarden.BitwardenCLI;
import com.mwdle.bitwarden.BitwardenCLIManager;
import com.mwdle.bitwarden.BitwardenSessionManager;
import com.mwdle.converters.CredentialProxy;
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
 * This class is a singleton discovered by Jenkins via its {@link Extension} annotation.
 * It makes the plugin's settings available on the "Configure System" page (/manage/configure).
 */
@Extension
@Symbol("bitwarden")
public class BitwardenConfig extends GlobalConfiguration {

    private static final Logger LOGGER = Logger.getLogger(BitwardenConfig.class.getName());
    /**
     * A transient, in-memory snapshot of the configuration as it was last loaded or saved.
     * Used for "dirty checking" to see if critical settings have changed.
     */
    private transient BitwardenConfig loadedConfig;

    /** The URL of the self-hosted Bitwarden/Vaultwarden server. */
    private String serverUrl;
    /** The Jenkins credential ID for the Bitwarden API Key (Client ID & Secret). */
    private String apiCredentialId;
    /** The Jenkins credential ID for the Bitwarden Master Password. */
    private String masterPasswordCredentialId;
    /* The cache duration in minutes for the list of item metadata. */
    private int cacheDuration = 5; // Default to 5 minutes

    /**
     * Called by Jenkins at startup to create an instance of this class.
     * The {@link #load()} method populates the fields from the saved XML configuration on disk.
     */
    public BitwardenConfig() {
        load();
        LOGGER.fine("BitwardenGlobalConfig loaded: serverUrl=" + serverUrl
                + ", apiCredentialId=" + apiCredentialId
                + ", masterPasswordCredentialId=" + masterPasswordCredentialId);
    }

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

    public int getCacheDuration() {
        return cacheDuration;
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
    public void setCacheDuration(int cacheDuration) {
        // Use a default if the user enters an invalid number.
        this.cacheDuration = (cacheDuration > 0) ? cacheDuration : 5;
    }

    /**
     * Creates a simple copy of this object for state comparison.
     */
    private BitwardenConfig snapshot() {
        BitwardenConfig snapshot = new BitwardenConfig();
        snapshot.serverUrl = this.serverUrl;
        snapshot.apiCredentialId = this.apiCredentialId;
        snapshot.masterPasswordCredentialId = this.masterPasswordCredentialId;
        return snapshot;
    }

    /**
     * A helper method to check if the essential configuration is present.
     */
    public boolean isConfigured() {
        return apiCredentialId != null
                && !apiCredentialId.isEmpty()
                && masterPasswordCredentialId != null
                && !masterPasswordCredentialId.isEmpty();
    }

    @Override
    public boolean configure(StaplerRequest2 req, JSONObject json) throws FormException {
        super.configure(req, json);
        save();
        return true;
    }

    @Override
    public void save() {
        super.save();
        boolean configChanged = loadedConfig == null
                || !Objects.equals(this.serverUrl, loadedConfig.serverUrl)
                || !Objects.equals(this.apiCredentialId, loadedConfig.apiCredentialId)
                || !Objects.equals(this.masterPasswordCredentialId, loadedConfig.masterPasswordCredentialId);
        if (isConfigured() && configChanged) {
            LOGGER.info("Plugin configuration saved successfully. Applying configuration...");
            Timer.get().submit(() -> {
                BitwardenSessionManager.getInstance().invalidateSessionToken();
                BitwardenCacheManager.getInstance().invalidateCache();
                if (BitwardenCLIManager.getInstance().provisionExecutable()) {
                    BitwardenCacheManager.getInstance().updateCache();
                }
            });
        }
        this.loadedConfig = snapshot();
    }

    /**
     * A credentials matcher for configuring the Bitwarden API and Master Password credentials that
     *  - includes all credentials in the SYSTEM and GLOBAL scopes
     *  - excludes credentials belonging to this provider to avoid a chicken-and-egg problem
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
     * Populates the dropdown list for the 'Bitwarden API Key Credential' field in the UI.
     * <p>
     * This method is called automatically by Jenkins's UI framework (Stapler)
     * because its name follows the convention {@code doFill<FieldName>Items}.
     *
     * @param context The current Jenkins context, injected by Stapler.
     * @param apiCredentialId The currently saved value of the field, for ensuring it's in the list.
     * @return A {@link ListBoxModel} containing the credential options.
     */
    @POST
    public ListBoxModel doFillApiCredentialIdItems(
            @AncestorInPath Jenkins context, @QueryParameter String apiCredentialId) {
        context.checkPermission(Jenkins.ADMINISTER);
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
     * Populates the dropdown list for the 'Bitwarden Master Password Credential' field in the UI.
     *
     * @param context The current Jenkins context, injected by Stapler.
     * @param masterPasswordCredentialId The currently saved value of the field.
     * @return A {@link ListBoxModel} containing the credential options.
     */
    @POST
    public ListBoxModel doFillMasterPasswordCredentialIdItems(
            @AncestorInPath Jenkins context, @QueryParameter String masterPasswordCredentialId) {
        context.checkPermission(Jenkins.ADMINISTER);
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
     * An action method called from the UI to trigger a non-destructive cache refresh.
     *
     * @return A FormValidation object indicating the result of the action.
     */
    @POST
    public FormValidation doRefreshCache() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
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
     * Checks and displays the current version of the installed Bitwarden CLI.
     */
    @POST
    public FormValidation doCheckCliVersion() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        try {
            String currentVersion = BitwardenCLI.version();
            return FormValidation.ok(Messages.validation_cliVersion(currentVersion));
        } catch (Exception e) {
            return FormValidation.error(Messages.validation_cliError(e.getMessage()));
        }
    }

    /**
     * Forces a new download and installation of the Bitwarden CLI.
     */
    @POST
    public FormValidation doForceUpdateCli() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
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
}
