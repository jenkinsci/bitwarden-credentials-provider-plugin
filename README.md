# Bitwarden Credentials Provider Plugin

[![Jenkins Plugin](https://img.shields.io/jenkins/plugin/v/bitwarden-credentials-provider.svg)](https://plugins.jenkins.io/bitwarden-credentials-provider)
[![CI Build Status](https://ci.jenkins.io/buildStatus/icon?job=Plugins/bitwarden-credentials-provider-plugin/main)](https://ci.jenkins.io/job/Plugins/job/bitwarden-credentials-provider-plugin/job/main/)
[![CD Build Status](https://github.com/jenkinsci/bitwarden-credentials-provider-plugin/actions/workflows/cd.yaml/badge.svg)](https://github.com/jenkinsci/bitwarden-credentials-provider-plugin/actions/workflows/cd.yaml)
[![Security Scan](https://github.com/jenkinsci/bitwarden-credentials-provider-plugin/actions/workflows/jenkins-security-scan.yaml/badge.svg)](https://github.com/jenkinsci/bitwarden-credentials-provider-plugin/actions/workflows/jenkins-security-scan.yaml)
[![License](https://img.shields.io/github/license/jenkinsci/bitwarden-credentials-provider-plugin.svg)](LICENSE)

The **Bitwarden Credentials Provider** is a [Jenkins](https://jenkins.io) plugin that integrates with [Bitwarden Password Manager](https://bitwarden.com) (and self-hosted [Vaultwarden](https://github.com/dani-garcia/vaultwarden)) to provide secrets as native Jenkins credentials.

This plugin integrates with personal vaults and organizations within the Password Manager product. It does **not** support the separate enterprise product, **Bitwarden Secrets Manager**.

> [!NOTE]
> This is a community-built plugin and is not affiliated with, sponsored, or endorsed by Bitwarden, Inc.

## How It Works

This plugin uses the official Bitwarden CLI (`bw`) as its engine for all interactions with your vault. Its architecture is designed for performance, security, and resilience.

1. **Bitwarden CLI Management:** The plugin manages its own copy of the `bw` executable for `x86_64`/`amd64` systems. Other architectures require manual provisioning (see [Manual CLI Provisioning](#manual-cli-provisioning-optional)).
2. **Efficient Caching:** On startup or after being configured, the plugin performs an initial `bw sync`. It then caches only the non-secret **metadata** (names, IDs, types) for the Jenkins credentials in memory.
3. **On-Demand Background Refresh:** When credentials are requested and the cached item list is older than the "Item List Cache Duration" setting, the plugin serves the existing list immediately and re-syncs with the Bitwarden server via `bw sync` in the background, keeping your credentials reasonably fresh without impacting performance from slow CLI operations.
4. **Live, On-Demand Secret Fetching:** Your actual secrets (passwords, keys, etc.) are **never** cached by Jenkins. They are fetched "live" from the CLI's secure, local database at the exact moment a build needs to use them.
5. **Network Resilience:** If the Bitwarden server goes offline or Jenkins loses internet, pipelines will continue working uninterrupted using the Bitwarden CLI's local database. (Note: A Jenkins controller restart still requires an internet connection for the initial login).

> [!WARNING]
> **Performance Consideration**
>
> The fetching of secrets involves a call to the Bitwarden CLI, which is a relatively slow operation (it can take several seconds). While the plugin's caching makes most operations fast, any operation in Jenkins that requires resolving a secret value from Bitwarden will incur this one-time performance cost.
>
> For this reason, it is not recommended to use credentials from this plugin for high-frequency operations, such as configuring the SCM for a GitHub Organization folder, which utilizes credentials multiple times on every scan.

## Getting Started

You must first configure the plugin's global settings under **Manage Jenkins > System > Bitwarden Credentials Provider Configuration**.

- **Bitwarden Server URL:** Defaults to the official Bitwarden USA cloud ([vault.bitwarden.com](https://vault.bitwarden.com)). Override it to use a self-hosted instance like Vaultwarden or the Bitwarden EU cloud.
- **Bitwarden API Key Credential:** Select a Jenkins "Username with password" credential that stores your Bitwarden Client ID and Client Secret.
    - *It is highly recommended to create this manually via the Jenkins UI (not JCasC) with **System** scope to prevent exposure to standard pipelines.*
- **Bitwarden Master Password Credential:** Select a Jenkins "Secret text" credential that stores your account's Master Password.
    - *It is highly recommended to create this manually via the Jenkins UI (not JCasC) with **System** scope to prevent exposure to standard pipelines.*
- **Item List Cache Duration:** Sets how long (in minutes) the cached list of vault items is considered fresh. When credentials are requested after this duration has passed, a background re-sync is triggered. Defaults to 5 minutes.
- **Bitwarden CLI Executable Path (Optional):** Provide the absolute path to a manually installed `bw` executable. See [Manual CLI Provisioning](#manual-cli-provisioning-optional).
- **File Credential Suffixes (Optional):** A comma-separated list of name suffixes (e.g., `.env,.yaml`) identifying which Secure Notes should be exposed as File credentials instead of Secret text.

You can verify that the configuration was applied successfully using the following steps:

1.  **Verify the Session:** Navigate to **Manage Jenkins > System > Bitwarden Credentials Provider Configuration > Advanced**. Click the **Verify Session** button.
    - A **success message** immediately confirms that your configuration is correct and the plugin is working.
    - If you see a **"No active session"** warning, this is normal right after startup. Wait up to a minute for the initial background sync to complete, then click the button again.

2.  **Check the Credentials Page:** If the session is active, navigate to the main **Credentials** page from the Jenkins dashboard. Your Bitwarden items should be listed there.

3.  **Check the Logs (if needed):** If the session is still not active after waiting, it likely indicates a configuration error (e.g., incorrect API key, master password, or server URL). Check the **Jenkins system log** for error messages from `com.mwdle.bitwarden` to diagnose the issue.

### Manual CLI Provisioning (Optional)

By default, this plugin automatically downloads and manages the Bitwarden CLI for `x86_64`/`amd64` architectures running Linux, macOS, and Windows.

However, there are two common scenarios where you must or may want to manually provision the CLI:

1. **ARM Architectures:** The automatic downloader can only fetch the `x86_64`/`amd64` build. Bitwarden's `arm64` binaries exist only as version-specific GitHub release assets, so ARM controllers need manual provisioning — download the appropriate `arm64` build (see the Dockerfile below) or run `npm install -g @bitwarden/cli`, then point the plugin to the executable path.
2. **Version Pinning & OSS Builds:** The plugin's automatic downloader fetches the latest non-OSS version of the CLI directly from Bitwarden's servers. If you need to pin a specific version, use the strict Open Source Software (OSS) build, or operate in an air-gapped environment, you can manage the installation yourself.

The following example `Dockerfile` demonstrates how to manually install and pin a specific version of the official **OSS build** from GitHub releases into an `x86_64` Jenkins controller image:

```Dockerfile
FROM jenkins/jenkins:lts

USER root

# Pin the Bitwarden CLI version for the Bitwarden Credentials Provider Plugin
ARG BW_CLI_VERSION="2026.8.0"

# Download the x86_64 BW CLI zip file directly from GitHub releases
RUN curl -Lso bw.zip "https://github.com/bitwarden/clients/releases/download/cli-v${BW_CLI_VERSION}/bw-oss-linux-${BW_CLI_VERSION}.zip" \
    && unzip bw.zip -d /usr/local/bin/ \
    && rm bw.zip \
    && chmod +x /usr/local/bin/bw

USER jenkins
```

Or, for an `arm64` Jenkins controller image (e.g., for an Apple Silicon machine), use an `arm64` version instead:

```Dockerfile
FROM jenkins/jenkins:lts

USER root

# Pin the Bitwarden CLI version for the Bitwarden Credentials Provider Plugin
ARG BW_CLI_VERSION="2026.8.0"

# Download the arm64 BW CLI zip file directly from GitHub releases
RUN curl -Lso bw.zip "https://github.com/bitwarden/clients/releases/download/cli-v${BW_CLI_VERSION}/bw-oss-linux-arm64-${BW_CLI_VERSION}.zip" \
    && unzip bw.zip -d /usr/local/bin/ \
    && rm bw.zip \
    && chmod +x /usr/local/bin/bw

USER jenkins
```

### Troubleshooting and Diagnostics

The plugin includes several actions under **Manage Jenkins > System > Bitwarden Credentials Provider Configuration > Advanced** to help you diagnose and quickly configure the plugin without needing to check the system log.

- **Verify Session:** Performs a check to see if the plugin currently has an active session with the Bitwarden CLI. This is the quickest way to confirm that the plugin is logged in and ready to serve credentials.
- **Check CLI Version:** Verifies that the Bitwarden CLI is installed and executable by Jenkins.
- **Update CLI:** Forces a fresh download of the latest official Bitwarden CLI. This is useful for updating the CLI to a newer version. (Disabled when a manual CLI path is configured.)
- **Sync Vault:** Forces the plugin to invalidate its current session and cached item list, re-authenticate, and re-sync with the Bitwarden server. Use this if you've made changes in your Bitwarden vault and want them to appear immediately.

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
# Configure the plugin within the `unclassified` section:
unclassified:
  bitwarden:
    # The URL of your self-hosted Bitwarden/Vaultwarden server.
    # Defaults to the official Bitwarden USA cloud (https://vault.bitwarden.com); set a self-hosted or Bitwarden EU cloud URL here to override it.
    serverUrl: "https://vault.example.com"
    # The Jenkins credential ID for your Bitwarden API Key.
    # It is recommended to create this credential manually via the Jenkins UI under SYSTEM (not global) scope.
    apiCredentialId: "bitwarden-api-key"
    # The Jenkins credential ID for your Bitwarden Master Password.
    # It is recommended to create this credential manually via the Jenkins UI under SYSTEM (not global) scope.
    masterPasswordCredentialId: "bitwarden-master-password"
    # (Optional) The absolute path to a manually installed `bw` executable.
    # Required for architectures other than x86_64/amd64, such as ARM/aarch64.
    cliExecutablePath: "/usr/local/bin/bw"
    # The time (in minutes) before the cached item list is considered stale and a background re-sync is triggered on the next request.
    # Defaults to 5 minutes.
    cacheDuration: 10
    # (Optional) Comma-separated list of suffixes for Secure Notes names to be treated as File credentials.
    # If omitted, defaults to treating all notes as String credentials.
    fileCredentialSuffixes: ".env,.properties,.yaml"

# The credentials section allows you to define your Bitwarden credentials via JCasC, if desired (NOT RECOMMENDED - keep reading).
# Provisioning your Bitwarden credentials via JCasC is inherently less secure than creating them manually via the Jenkins UI.
# It is HIGHLY RECOMMENDED to create the necessary credentials manually via the Jenkins UI instead of using the JCasC code below.
credentials:
  system:
    domainCredentials:
      - credentials:
          - usernamePassword:
              scope: SYSTEM
              id: "bitwarden-api-key"
              username: "${BITWARDEN_CLIENT_ID}" # Uses value provided by environment variable
              password: "${BITWARDEN_CLIENT_SECRET}" # Uses value provided by environment variable
              description: "Bitwarden CLI API Credentials"
          - string:
              scope: SYSTEM
              id: "bitwarden-master-password"
              secret: "${BITWARDEN_MASTER_PASSWORD}" # Uses value provided by environment variable
              description: "Bitwarden CLI Master Password"
```

After Jenkins loads your JCasC configuration, the plugin will attempt to log in and perform an initial sync.

## Read-Only Credential Store

> [!IMPORTANT]
> The Bitwarden credential store in the Jenkins UI is **read-only**. This plugin only provides a view of the secrets in your vault; it does not manage them.
>
> If you attempt to create/update/delete a credential within the Bitwarden store, Jenkins will show a harmless error, and the credential will **not** be created or saved.
>
> All credential management must be done directly in your Bitwarden vault. The changes will appear in Jenkins after the next cache refresh.

## Usage in Pipelines

This plugin intelligently exposes every item in your vault as a native Jenkins credential. To handle items that share the same name, it uses the following rule:

- If a Bitwarden item's name is **unique** in your vault, its `credentialsId` in Jenkins is its **Bitwarden Name**.
- If a Bitwarden item's name is **not unique**, its `credentialsId` in Jenkins is its **Bitwarden UUID**.

It is easy to find the correct ID via the Jenkins UI. The description for each credential displays its name and UUID, and indicates if the name is not unique.

**Example: A Uniquely Named Secret**

You can reference this item directly by its name in your `Jenkinsfile`, for example:

```groovy
// Jenkinsfile
withCredentials([string(credentialsId: 'My Production API Key', variable: 'API_KEY')]) {
    sh 'echo "The secret API key is available."'
}
```

**Example: Handling a Non-Unique Name**

Imagine you have two items named "Docker Hub". In the Jenkins UI, they would appear like this:

- ID: `a1b2c3d4-...`, Name: `Docker Hub`, Description: `Docker Hub (BW ID: a1b2c3d4-..., non-unique name)`
- ID: `e5f6a1b2-...`, Name: `Docker Hub`, Description: `Docker Hub (BW ID: e5f6a1b2-..., non-unique name)`

To access either, you would copy its UUID from the UI and use that as the `credentialsId`, for example:

```groovy
// Jenkinsfile
withCredentials([usernamePassword(credentialsId: 'e5f6a1b2-c3d4-e5f6-a1b2-c3d4e5f6a1b2', usernameVariable: 'USER', passwordVariable: 'PASS')]) {
    sh 'echo "Logging in with the specific Docker Hub account..."'
}
```

To avoid having to fetch secrets by their Bitwarden UUID, the solution is simple: _always choose a unique name for each item in your Bitwarden vault_.

### Usage in Pipeline Parameters

This plugin populates `credentials()` parameters, but you **must omit the `credentialType` attribute** (If you are configuring the parameter through the Jenkins UI, you must set the **Credential type** dropdown to **"Any"**).

This is because the plugin loads credentials dynamically, and they are not recognized by the `credentialType` filter (which expects a concrete credential implementation class).

The trade-off is that the parameter dropdown will list _all_ Jenkins credentials of _all types_ (e.g., Secret Text, SSH Keys). Use the `description` field to guide users.

**Example `Jenkinsfile`:**

```groovy
pipeline {
    agent any
    parameters {
        credentials(
                name: 'DOCKER_CREDENTIALS_ID',
                // Note: credentialType is OMITTED.
                // This allows dynamically-provided credentials (like Bitwarden's) to appear.
                description: 'Select a "Username with password" credential for Docker',
                defaultValue: 'docker-hub',
                required: true
        )
    }
    stages {
        stage('Example') {
            steps {
                // The parameter is then used just like any other variable
                withCredentials([usernamePassword(credentialsId: params.DOCKER_CREDENTIALS_ID, usernameVariable: 'DOCKER_USER', passwordVariable: 'DOCKER_PASS')]) {
                    sh 'echo "Using credentials..."'
                }
            }
        }
    }
}
```

## Supported Credential Types

The plugin automatically converts Bitwarden items into the following Jenkins credential types.

| Bitwarden Item Type | Jenkins Credential Type               | Notes                                                                                                                                                                    |
| ------------------- | ------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Login               | `StandardUsernamePasswordCredentials` | If the username or password field is missing, it will be treated as an empty string.                                                                                     |
| Secure Note         | `StringCredentials`                   | The default for any secure note. The Bitwarden Secure Note character limit applies here.                                                                                 |
| Secure Note         | `FileCredentials`                     | If the note's name ends with a user-configured suffix (e.g., `.env`). The Bitwarden Secure Note character limit applies here.                                            |
| SSH Key             | `SSHUserPrivateKey`                   | The username is parsed from the public key's comment field. If unable to parse, the Jenkins `SSHUserPrivateKey` defaults to the username the Jenkins Controller runs as. |

Bitwarden **Card** and **Identity** items are not currently supported and are ignored.

## License

This project is licensed under the Apache License 2.0. See the [LICENSE](LICENSE) file for details.

## Contributing

This plugin is maintained by a single developer. While every effort is made to test thoroughly and keep the code well-organized and documented so others can understand it, bugs may still occur.

**Found a Bug?**

If you encounter any issues, please help improve the plugin by opening an issue on the [GitHub Issues](https://github.com/jenkinsci/bitwarden-credentials-provider-plugin/issues) page.

**Want to Add a Feature or Fix?**

Pull Requests for realistic, reasonable, and well-tested improvements are welcome. I will do my best to review them in a timely manner.
