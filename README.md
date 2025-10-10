# Bitwarden Credentials Provider Plugin

> [!NOTE]
> This is a third-party plugin and is not affiliated with, sponsored, or endorsed by Bitwarden, Inc.

[![GitHub release](https://img.shields.io/github/release/jenkinsci/bitwarden-credentials-provider-plugin.svg?label=release)](https://github.com/jenkinsci/bitwarden-credentials-provider-plugin/releases/latest)
[![Jenkins Plugin Installs](https://img.shields.io/jenkins/plugin/i/bitwarden-credentials-provider.svg?color=blue)](https://plugins.jenkins.io/bitwarden-credentials-provider)

The **Bitwarden Credentials Provider** is a [Jenkins](https://jenkins.io) plugin that dynamically exposes items from a **Bitwarden Password Manager** vault (including self-hosted [Vaultwarden](https://github.com/dani-garcia/vaultwarden)) as native Jenkins credentials. It allows pipeline authors to access any secret on the fly, without requiring an administrator to manually create or sync credentials in the Jenkins UI.

This plugin integrates with personal vaults and organizations within the Password Manager product. It does **not** support the separate enterprise product, **Bitwarden Secrets Manager**.

## Table of Contents

- [How It Works](#how-it-works)
- [Getting Started](#getting-started)
- [Usage in Pipelines](#usage-in-pipelines)
- [Supported Credential Types](#supported-credential-types)
- [Configuration as Code (JCasC)](#configuration-as-code-jcasc)
- [License](#license)

## How It Works

This plugin uses the official Bitwarden CLI (`bw`) as its engine for all interactions with your vault. Its architecture is designed for performance, security, and resilience.

1.  **Bitwarden CLI Management:** The plugin manages its own copy of the `bw` executable for `x86_64` systems. Other architectures require manual installation and providing the path to the executable (see Getting Started).
2.  **Efficient Caching:** On startup or after being configured, the plugin performs an initial `bw sync`. It then caches only the non-secret **metadata** (names, IDs, types) in Jenkins.
3.  **Persistence:** The metadata cache is persisted to a file on the Jenkins controller. This allows Jenkins to start up and serve credentials instantly.
4.  **Background Refresh:** The local vault is automatically re-synced with the Bitwarden server via `bw sync` in the background based on the "Cache Duration" setting, keeping your credentials reasonably fresh without impacting performance from slow CLI operations.
5.  **Live, On-Demand Secret Fetching:** Your actual secrets (passwords, keys, etc.) are **never** cached by Jenkins. They are fetched "live" from the CLI's secure, local database at the exact moment a build needs to use them.
6.  **Offline Access:** The Bitwarden CLI's local vault ensures that the plugin will continue functioning even if the Bitwarden server is unreachable or the controller is offline.

> [!WARNING]
> **Performance Consideration**
>
> The "live" fetching of secrets (Step 5) involves a call to the Bitwarden CLI, which is a relatively slow operation (it can take several seconds). While the plugin's caching makes most operations fast, any step in a pipeline that resolves a secret will incur this one-time performance cost.
>
> For this reason, it is not recommended to use credentials from this plugin for high-frequency operations, such as configuring the SCM for a GitHub Organization folder, which utilizes credentials multiple times on every scan.

## Getting Started

You must first configure the plugin's global settings in **Manage Jenkins > Configure System**.

-   **Bitwarden Server URL:** For self-hosted instances like Vaultwarden. Leave blank for the official Bitwarden cloud.
-   **Bitwarden API Key Credential:** Select a Jenkins "Username with password" credential that stores your Bitwarden Client ID and Client Secret.
-   **Bitwarden Master Password Credential:** Select a Jenkins "Secret text" credential that stores your account's Master Password.
-   **Cache Duration:** Sets how often the plugin will sync with the Bitwarden server in the background.
-   **Bitwarden CLI Executable Path: (Optional)** Provide the absolute path to a manually installed bw executable. This is required for Jenkins controllers running on CPU architectures for which there is no direct `bw` CLI download (e.g., `ARM`/`aarch64`).

> [!IMPORTANT]
> **Service Account Recommended**
>
> Because this plugin requires an account's Master Password, it is recommended that you **do not use your primary, personal Bitwarden account**.
>
> The best practice is to create a dedicated service account (a separate Bitwarden user) and grant it read-only access to only the secrets it needs using a **Bitwarden Organization**. You can then control which secrets the Jenkins user can access by placing them into specific **Collections** within that organization.
>
> **Note:** While creating an organization is free in self-hosted **Vaultwarden**, sharing with more than one other user on the official **Bitwarden** cloud requires a paid plan.

### Configuration as Code (JCasC)

This plugin is fully compatible with the Jenkins Configuration as Code plugin and uses the symbol `bitwarden`.

**Example `jenkins.yaml`:**
```yaml
# The credentials section allows you to define your Bitwarden credentials within JCasC, if desired.
# Please note that defining your credentials within JCasC (even if passing in from environment variable)
# is inherently less secure than creating them manually via the Jenkins UI.
credentials:
  system:
    domainCredentials:
      - credentials:
          - usernamePassword:
              scope: GLOBAL
              id: "bitwarden-api-key"
              username: "${BITWARDEN_CLIENT_ID}" # Uses value provided by environment variable
              password: "${BITWARDEN_CLIENT_SECRET}" # Uses value provided by environment variable
              description: "Bitwarden CLI API Credentials"
          - string:
              scope: GLOBAL
              id: "bitwarden-master-password"
              secret: '${BITWARDEN_MASTER_PASSWORD}' # Uses value provided by environment variable
              description: "Bitwarden CLI Master Password"
              
# Configure the plugin within the `unclassified` section:          
unclassified:
  bitwarden:
    # The URL of your self-hosted Bitwarden/Vaultwarden server.
    # Leave blank for the official Bitwarden cloud.
    serverUrl: "https://vault.example.com"
    # The Jenkins credential ID for your Bitwarden API Key.
    apiCredentialId: "bitwarden-api-key"
    # The Jenkins credential ID for your Bitwarden Master Password.
    masterPasswordCredentialId: "bitwarden-master-password"
    # (Optional) The absolute path to a manually installed `bw` executable.
    # Required for non-x86_64 architectures like ARM/aarch64.
    cliExecutablePath: "/usr/local/bin/bw"
    # How often the plugin automatically syncs with the Bitwarden server (in minutes).
    cacheDuration: 10
    # Comma-separated list of suffixes for Secure Notes names to be treated as File Credentials.
    fileCredentialSuffixes: ".env,.properties,.yaml"
```

After Jenkins starts with your JCasC configuration, the plugin will attempt to log in and perform an initial sync. You can verify that the configuration was applied successfully using the following steps:

1.  **Verify the Session:** Navigate to **Manage Jenkins > System > Bitwarden Credentials Provider Configuration**. In the "Advanced" section, click the **Verify Session Status** button.
    * A **success message** immediately confirms that your configuration is correct and the plugin is working.
    * If you see a **"No active session"** warning, this is normal right after startup. Wait up to a minute for the initial background sync to complete, then click the button again.

2.  **Check the Credentials Page:** If the session is active, navigate to the main **Credentials** page from the Jenkins dashboard. Your Bitwarden items should be listed there.

3.  **Check the Logs (if needed):** If the session is still not active after waiting, it likely indicates a configuration error (e.g., incorrect API key, master password, or server URL). Check the **Jenkins system log** for error messages from `com.mwdle.bitwarden` to diagnose the issue.

### Troubleshooting and Diagnostics

The plugin includes several actions in the **Manage Jenkins > System > Bitwarden Credentials Provider > Advanced** section to help you diagnose and quickly configure the plugin without needing to check the system log.

- **Verify Session Status:** Performs a check to see if the plugin currently has an active session with the Bitwarden CLI. This is the quickest way to confirm that the plugin is logged in and ready to serve credentials.
- **Check Version:** Verifies that the Bitwarden CLI is installed and executable by Jenkins.
- **Download Latest:** Forces a fresh download of the latest official Bitwarden CLI. This is useful for updating the CLI to a newer version.
- **Verify Session Status:** Performs a fast, read-only check to see if the plugin currently has an active, unlocked session with the Bitwarden CLI. This is the quickest way to confirm that the plugin is logged in and ready to serve credentials.
- **Refresh Now:** Forces the plugin to invalidate its current session and credential list and start a new sync in the background. Use this if you've made changes in your Bitwarden vault and want them to appear immediately.

## Read-Only Credential Store

> [!IMPORTANT]
> The Bitwarden credential store in the Jenkins UI is **read-only**. This plugin only provides a view of the secrets in your vault; it does not manage them.
>
> If you attempt to create/update/delete a credential within the Bitwarden store, Jenkins will show a harmless error, and the credential will **not** be created or saved.
>
> All credential management must be done directly in your Bitwarden vault. The changes will then appear in Jenkins after the next cache refresh.

## Usage in Pipelines

This plugin intelligently exposes every item in your vault as a native Jenkins credential. To handle items that share the same name, it uses the following rule:

-   If an item's name is **unique** in your vault, its `credentialsId` in Jenkins is its **Name**.
-   If an item's name is **not unique**, its `credentialsId` in Jenkins is its **UUID**.

The Jenkins UI makes it easy to find the correct ID. The description for every credential shows both its name and its UUID, and will indicate if the name is non-unique.

**Example: A Uniquely Named Secret**

You can reference this item directly by its name in your `Jenkinsfile`.

```groovy
// Jenkinsfile
withCredentials([string(credentialsId: 'My Production API Key', variable: 'API_KEY')]) {
    sh 'echo "The secret API key is available."'
}
```

**Example: Handling a Non-Unique Name**

Imagine you have two items named "Docker Hub". In the Jenkins UI, they would appear like this:

* ID: `a1b2c3d4-...`, Name: `Docker Hub (BW ID: a1b2c3d4-..., non-unique name)`
* ID: `e5f6a1b2-...`, Name: `Docker Hub (BW ID: e5f6a1b2-..., non-unique name)`

To access either one, you would copy its UUID from the UI and use that as the `credentialsId`.

```groovy
// Jenkinsfile
withCredentials([usernamePassword(credentialsId: 'e5f6a1b2-c3d4-e5f6-a1b2-c3d4e5f6a1b2', usernameVariable: 'USER', passwordVariable: 'PASS')]) {
    sh 'echo "Logging in with the specific Docker Hub account..."'
}
```

To avoid having to fetch secrets by their Bitwarden UUID, the solution is simple: *Always set a unique name for each item in your Bitwarden vault*.

## Supported Credential Types

The plugin automatically converts Bitwarden items into the following Jenkins credential types.

| Bitwarden Item Type | Jenkins Credential Type               | Notes                                                                                                                                                                    |
|---------------------|---------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Login               | `StandardUsernamePasswordCredentials` | The login item must contain a username or a password. If a field is missing, it will be treated as an empty string.                                                      |
| Secure Note         | `StringCredentials`                   | The default for any secure note. The Bitwarden Secure Note character limit applies here.                                                                                 |
| Secure Note         | `FileCredentials`                     | If the note's name ends with a user-configured suffix (e.g., `.env`). The Bitwarden Secure Note character limit applies here.                                            |
| SSH Key             | `SSHUserPrivateKey`                   | The username is parsed from the public key's comment field. If unable to parse, the Jenkins `SSHUserPrivateKey` defaults to the username the Jenkins Controller runs as. |

## License

This project is licensed under the Apache License 2.0. See the [LICENSE](LICENSE) file for details.

## Contributing

This plugin is maintained by a single developer. While every effort is made to test thoroughly and keep the code well-organized and documented so others can understand it, bugs may still occur.

**Found a Bug?**

If you encounter any issues, please help improve the plugin by opening an issue on the [GitHub Issues](https://github.com/jenkinsci/bitwarden-credentials-provider-plugin/issues) page.

**Want to Add a Feature or Fix?**

Pull Requests for realistic, reasonable, and well-tested improvements are welcome. I will do my best to review them in a timely manner.

