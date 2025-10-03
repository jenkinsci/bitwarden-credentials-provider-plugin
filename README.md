# Bitwarden Credentials Provider Plugin

> [!NOTE]
> This is a third-party plugin and is not affiliated with, sponsored, or endorsed by Bitwarden, Inc.

[![Jenkins Plugin](https://img.shields.io/jenkins/plugin/v/bitwarden-credentials-provider.svg)](https://plugins.jenkins.io/bitwarden-credentials-provider)
[![GitHub release](https://img.shields.io/github/release/mwdle/bitwarden-credentials-provider-plugin.svg?label=release)](https://github.com/mwdle/bitwarden-credentials-provider-plugin/releases/latest)
[![Jenkins Plugin Installs](https://img.shields.io/jenkins/plugin/i/bitwarden-credentials-provider.svg?color=blue)](https://plugins.jenkins.io/bitwarden-credentials-provider)

The **Bitwarden Credentials Provider** is a [Jenkins](https://jenkins.io) plugin that dynamically exposes the items in your [Bitwarden](https://bitwarden.com/) personal vault as a native Jenkins credential. It allows pipeline authors to access any secret on the fly by its name or ID, without requiring an administrator to manually create or sync credentials in the Jenkins UI.

> [!NOTE]
> This plugin is designed specifically for the **Bitwarden Password Manager** product (or self-hosted [Vaultwarden](https://github.com/dani-garcia/vaultwarden)) and operates on **personal vaults**.
> It does **not** integrate with the separate enterprise product **Bitwarden Secrets Manager**.

## Table of Contents

- [How It Works](#how-it-works)
- [Getting Started](#getting-started)
- [Usage in Pipelines](#usage-in-pipelines)
- [Supported Credential Types](#supported-credential-types)
- [Configuration as Code (JCasC)](#configuration-as-code-jcasc)
- [License](#license)

## How It Works

This plugin uses the official Bitwarden CLI (`bw`) as its engine for all interactions with your vault. Its architecture is designed for performance, security, and resilience.

1.  **Isolated Environment:** The plugin manages its own copy of the `bw` executable and maintains its own isolated data directory. This ensures it never interferes with a system-level Bitwarden CLI installation.
2.  **Efficient Caching:** On startup or after being configured, the plugin performs an initial `bw sync`. It then caches only the non-secret **metadata** (names, IDs, types) in Jenkins.
3.  **Persistence and Offline Access:** The metadata cache is persisted to a file on the Jenkins controller. This allows Jenkins to start up and serve credentials instantly, even if the Bitwarden server is unreachable or the controller is offline.
4.  **Background Refresh:** The local vault is automatically re-synced with the Bitwarden server in the background based on the "Cache Duration" setting, keeping your credentials reasonably fresh without impacting performance.
5.  **Live, On-Demand Secret Fetching:** Your actual secrets (passwords, keys, etc.) are **never** cached by Jenkins. They are fetched "live" from the CLI's secure, local database at the exact moment a build needs to use them.

## Getting Started

You must first configure the plugin's global settings in **Manage Jenkins > Configure System**.

-   **Bitwarden Server URL:** For self-hosted instances like Vaultwarden. Leave blank for the official Bitwarden cloud.
-   **Bitwarden API Key Credential:** Select a Jenkins "Username with password" credential that stores your Bitwarden service account's Client ID and Client Secret.
-   **Bitwarden Master Password Credential:** Select a Jenkins "Secret text" credential that stores your service account's Master Password.
-   **Cache Duration:** Sets how often the plugin will sync with the Bitwarden server in the background.

### Configuration as Code (JCasC)

This plugin is fully compatible with the Jenkins Configuration as Code plugin and uses the symbol `bitwarden`.

**Example `jenkins.yaml`:**
```yaml
credentials:
  system:
    stores:
      jenkins:
        credentials:
          - usernamePassword:
              id: "bitwarden-api-key"
              scope: GLOBAL
              username: "client.your-client-id"
              password: "${BITWARDEN_CLIENT_SECRET}"
          - string:
              id: "bitwarden-master-password"
              scope: GLOBAL
              secret: "${BITWARDEN_MASTER_PASSWORD}"
unclassified:
  bitwarden:
    # The URL of your self-hosted Bitwarden/Vaultwarden server.
    # Leave blank for the official Bitwarden cloud.
    serverUrl: "https://vault.example.com"
  
    # The Jenkins credential ID for your Bitwarden API Key.
    apiCredentialId: "bitwarden-api-key"
  
    # The Jenkins credential ID for your Bitwarden Master Password.
    masterPasswordCredentialId: "bitwarden-master-password"
  
    # How often the plugin automatically syncs with the Bitwarden server (in minutes).
    cacheDuration: 10
  
    # Comma-separated list of suffixes for Secure Notes names to be treated as File Credentials.
    fileCredentialSuffixes: ".env,.properties,.yaml"
```

After saving the configuration, the plugin will attempt to log in and perform an initial sync. You can verify success by checking the Jenkins system log or by navigating to the main Credentials page after a few moments to see your Bitwarden items.

## Read-Only Credential Store

> [!IMPORTANT]
> The Bitwarden credential store in the Jenkins UI is **read-only**.
>
> This plugin provides a view of the secrets in your vault; it does not manage them.
>
> - **Creating Credentials:** If you attempt to create a new credential within the Bitwarden store, Jenkins will show a harmless error, and the credential will **not** be created or saved.
> - **Updating/Deleting Credentials:** Attempting to modify or delete credentials from this store will fail.
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

* ID: `Docker Hub`, Description: `Docker Hub (BW ID: a1b2c3d4-...)`
* ID: `e5f6a1b2-...`, Description: `Docker Hub (BW ID: e5f6a1b2-..., non-unique name)`

To access the second one, you would copy its UUID from the UI and use that as the `credentialsId`.

```groovy
// Jenkinsfile
withCredentials([usernamePassword(credentialsId: 'e5f6a1b2-c3d4-e5f6-a1b2-c3d4e5f6a1b2', usernameVariable: 'USER', passwordVariable: 'PASS')]) {
    sh 'echo "Logging in with the specific Docker Hub account..."'
}
```

## Supported Credential Types

The plugin automatically converts Bitwarden items into the following Jenkins credential types.

| Bitwarden Item Type | Jenkins Credential Type               | Notes                                                                   |
|---------------------|---------------------------------------|-------------------------------------------------------------------------|
| Login               | `StandardUsernamePasswordCredentials` |                                                                         |
| Secure Note         | `StringCredentials`                   | The default for any secure note.                                        |
| Secure Note         | `FileCredentials`                     | If the note's name ends with a user-configured suffix (e.g., `.env`).   |
| SSH Key             | `SSHUserPrivateKey`                   | The username is parsed from the public key's comment field.             |

## License

This project is licensed under the Apache License 2.0. See the [LICENSE](LICENSE) file for details.

## Contributing

This plugin is maintained by a single developer. While every effort is made to test thoroughly and keep the code well-organized and documented so others can understand it, bugs may still occur.

**Found a Bug?**

If you encounter any issues, please help improve the plugin by opening an issue on the [GitHub Issues](https://github.com/mwdle/bitwarden-credentials-provider-plugin/issues) page.

**Want to Add a Feature or Fix?**

Pull Requests for realistic, reasonable, and well-tested improvements are welcome. I will do my best to review them in a timely manner.

