package com.mwdle.bitwarden.converters;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.mwdle.bitwarden.model.BitwardenItem;
import com.mwdle.bitwarden.model.BitwardenItemMetadata;
import com.mwdle.bitwarden.model.BitwardenItemType;
import com.mwdle.bitwarden.model.BitwardenLogin;
import hudson.model.Descriptor;
import hudson.util.Secret;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import jenkins.model.Jenkins;
import jenkins.security.ConfidentialStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for the LoginConverter class.
 */
@DisplayName("LoginConverter")
class LoginConverterTest {

    @TempDir
    Path tempDir;

    @Mock
    private Jenkins jenkinsMock;

    @Mock
    private ConfidentialStore confidentialStoreMock;

    private MockedStatic<Jenkins> mockedJenkins;
    private MockedStatic<ConfidentialStore> mockedConfidentialStore;
    private MockedStatic<Secret> mockedSecret;
    private LoginConverter converter;
    private AutoCloseable closeable;

    @BeforeEach
    void setUp() {
        closeable = MockitoAnnotations.openMocks(this);

        // Mock Jenkins and its dependencies
        mockedJenkins = mockStatic(Jenkins.class);
        when(Jenkins.get()).thenReturn(jenkinsMock);
        when(jenkinsMock.getLegacyInstanceId()).thenReturn("test-instance-id");
        when(jenkinsMock.getRootDir()).thenReturn(tempDir.toFile());

        // Mock ConfidentialStore as it's a dependency of Secret
        mockedConfidentialStore = mockStatic(ConfidentialStore.class);
        when(ConfidentialStore.get()).thenReturn(confidentialStoreMock);

        // Mock the static Secret.fromString method to isolate from Jenkins' encryption
        mockedSecret = mockStatic(Secret.class);
        mockedSecret.when(() -> Secret.fromString(anyString())).thenAnswer(invocation -> {
            String plainText = invocation.getArgument(0);
            Secret secretMock = mock(Secret.class);
            when(secretMock.getPlainText()).thenReturn(plainText);
            return secretMock;
        });

        converter = new LoginConverter();
    }

    @AfterEach
    void tearDown() throws Exception {
        mockedJenkins.close();
        mockedConfidentialStore.close();
        mockedSecret.close();
        closeable.close();
    }

    @Nested
    @DisplayName("canConvert(BitwardenItemMetadata) method")
    class CanConvertMetadata {
        @Test
        @DisplayName("should return true for LOGIN type")
        void shouldReturnTrueForLoginType() {
            BitwardenItemMetadata metadata = mock(BitwardenItemMetadata.class);
            when(metadata.type).thenReturn(BitwardenItemType.LOGIN);
            assertTrue(converter.canConvert(metadata));
        }

        @Test
        @DisplayName("should return false for non-LOGIN type")
        void shouldReturnFalseForOtherTypes() {
            BitwardenItemMetadata metadata = mock(BitwardenItemMetadata.class);
            when(metadata.type).thenReturn(BitwardenItemType.SECURE_NOTE);
            assertFalse(converter.canConvert(metadata));
        }
    }

    @Nested
    @DisplayName("canConvert(BitwardenItem) method")
    class CanConvertItem {

        @Test
        @DisplayName("should return true for a valid login item")
        void shouldReturnTrueForValidLoginItem() {
            BitwardenLogin login = mock(BitwardenLogin.class);
            Secret userSecret = Secret.fromString("user");
            when(login.username()).thenReturn(userSecret);
            BitwardenItem item = mock(BitwardenItem.class);
            when(item.login).thenReturn(login);
            assertTrue(converter.canConvert(item));
        }

        @Test
        @DisplayName("should return true for a login item with only a password")
        void shouldReturnTrueForLoginItemWithOnlyPassword() {
            BitwardenLogin login = mock(BitwardenLogin.class);
            Secret passSecret = Secret.fromString("pass");
            when(login.password()).thenReturn(passSecret);
            BitwardenItem item = mock(BitwardenItem.class);
            when(item.login).thenReturn(login);
            assertTrue(converter.canConvert(item));
        }

        @Test
        @DisplayName("should return false for a non-login item")
        void shouldReturnFalseForNonLoginItem() {
            BitwardenItem item = mock(BitwardenItem.class);
            when(item.login).thenReturn(null);
            assertFalse(converter.canConvert(item));
        }

        @Test
        @DisplayName("should return false for a login item with no username or password")
        void shouldReturnFalseForEmptyLoginItem() {
            BitwardenLogin login = mock(BitwardenLogin.class);
            BitwardenItem item = mock(BitwardenItem.class);
            when(item.login).thenReturn(login);
            assertFalse(converter.canConvert(item));
        }
    }

    @Nested
    @DisplayName("createProxy() method")
    class CreateProxy {
        @Test
        @DisplayName("should create a valid proxy when descriptor is found")
        void shouldCreateProxySuccessfully() {
            // GIVEN: The concrete Descriptor implementation
            Descriptor<?> testDescriptor = new UsernamePasswordCredentialsImpl.DescriptorImpl();
            when(jenkinsMock.getDescriptor(UsernamePasswordCredentialsImpl.class))
                    .thenReturn(testDescriptor);
            BitwardenItemMetadata metadata = mock(BitwardenItemMetadata.class);
            when(metadata.id).thenReturn("item-id");
            when(metadata.name).thenReturn("Item Name");

            // WHEN
            StandardUsernamePasswordCredentials proxy = converter.createProxy("cred-id", metadata);

            // THEN
            assertNotNull(proxy);
            assertInstanceOf(CredentialProxy.class, Proxy.getInvocationHandler(proxy));
        }

        @Test
        @DisplayName("should return null when descriptor is not found")
        void shouldReturnNullWhenDescriptorIsMissing() {
            // GIVEN
            when(jenkinsMock.getDescriptor(UsernamePasswordCredentialsImpl.class))
                    .thenReturn(null);
            BitwardenItemMetadata metadata = mock(BitwardenItemMetadata.class);

            // WHEN
            StandardUsernamePasswordCredentials proxy = converter.createProxy("cred-id", metadata);

            // THEN
            assertNull(proxy);
        }
    }

    @Nested
    @DisplayName("convert() method")
    class Convert {

        @Test
        @DisplayName("should convert a valid item to StandardUsernamePasswordCredentials")
        void shouldConvertValidItem() {
            // GIVEN
            BitwardenLogin login = mock(BitwardenLogin.class);
            Secret userSecret = Secret.fromString("test-user");
            Secret passSecret = Secret.fromString("test-pass");
            when(login.username()).thenReturn(userSecret);
            when(login.password()).thenReturn(passSecret);
            BitwardenItem item = mock(BitwardenItem.class);
            when(item.login).thenReturn(login);

            // WHEN
            StandardUsernamePasswordCredentials credential = converter.convert("cred-id", "A test credential", item);

            // THEN
            assertNotNull(credential);
            assertEquals("cred-id", credential.getId());
            assertEquals("A test credential", credential.getDescription());
            assertEquals("test-user", credential.getUsername());
            assertEquals("test-pass", credential.getPassword().getPlainText());
        }

        @Test
        @DisplayName("should handle a null username gracefully")
        void shouldHandleNullUsername() {
            // GIVEN
            BitwardenLogin login = mock(BitwardenLogin.class);
            Secret passSecret = Secret.fromString("test-pass");
            when(login.password()).thenReturn(passSecret);
            BitwardenItem item = mock(BitwardenItem.class);
            when(item.login).thenReturn(login);

            // WHEN
            StandardUsernamePasswordCredentials credential = converter.convert("cred-id", "A test credential", item);

            // THEN
            assertNotNull(credential);
            assertEquals("", credential.getUsername());
            assertEquals("test-pass", credential.getPassword().getPlainText());
        }

        @Test
        @DisplayName("should handle a null password gracefully")
        void shouldHandleNullPassword() {
            // GIVEN
            BitwardenLogin login = mock(BitwardenLogin.class);
            Secret userSecret = Secret.fromString("test-user");
            when(login.username()).thenReturn(userSecret);
            BitwardenItem item = mock(BitwardenItem.class);
            when(item.login).thenReturn(login);

            // WHEN
            StandardUsernamePasswordCredentials credential = converter.convert("cred-id", "A test credential", item);

            // THEN
            assertNotNull(credential);
            assertEquals("test-user", credential.getUsername());
            assertEquals("", credential.getPassword().getPlainText());
        }
    }
}
