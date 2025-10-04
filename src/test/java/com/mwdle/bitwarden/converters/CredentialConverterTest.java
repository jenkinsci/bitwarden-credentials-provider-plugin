package com.mwdle.bitwarden.converters;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.*;

import com.mwdle.bitwarden.model.BitwardenItem;
import com.mwdle.bitwarden.model.BitwardenItemMetadata;
import hudson.ExtensionList;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.*;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for the abstract CredentialConverter class.
 * <p>
 * This test suite focuses specifically on the static {@code findConverter} factory methods,
 * verifying that they correctly query the Jenkins {@link ExtensionList} to find a suitable
 * converter.
 */
@DisplayName("CredentialConverter")
class CredentialConverterTest {

    @Mock
    private Jenkins jenkinsMock;

    @Mock
    private ExtensionList<CredentialConverter> extensionListMock;

    private MockedStatic<Jenkins> mockedJenkins;
    private AutoCloseable closeable;

    @BeforeEach
    void setUp() {
        closeable = MockitoAnnotations.openMocks(this);

        mockedJenkins = mockStatic(Jenkins.class);
        when(Jenkins.get()).thenReturn(jenkinsMock);
        when(jenkinsMock.getExtensionList(CredentialConverter.class)).thenReturn(extensionListMock);
    }

    @AfterEach
    void tearDown() throws Exception {
        mockedJenkins.close();
        closeable.close();
    }

    @Nested
    @DisplayName("findConverter(BitwardenItemMetadata)")
    class FindConverterByMetadata {
        @Test
        @DisplayName("should return the first matching converter")
        void shouldReturnFirstMatch() {
            // GIVEN
            BitwardenItemMetadata metadata = mock(BitwardenItemMetadata.class);
            CredentialConverter nonMatchingConverter = mock(CredentialConverter.class);
            when(nonMatchingConverter.canConvert(metadata)).thenReturn(false);
            CredentialConverter matchingConverter = mock(CredentialConverter.class);
            when(matchingConverter.canConvert(metadata)).thenReturn(true);
            CredentialConverter anotherConverter = mock(CredentialConverter.class);

            when(extensionListMock.stream())
                    .thenReturn(Stream.of(nonMatchingConverter, matchingConverter, anotherConverter));

            // WHEN
            CredentialConverter result = CredentialConverter.findConverter(metadata);

            // THEN
            assertSame(matchingConverter, result);
            verify(anotherConverter, never()).canConvert(metadata);
        }

        @Test
        @DisplayName("should return null if no converters match")
        void shouldReturnNullIfNoMatch() {
            // GIVEN
            BitwardenItemMetadata metadata = mock(BitwardenItemMetadata.class);
            CredentialConverter converter1 = mock(CredentialConverter.class);
            when(converter1.canConvert(metadata)).thenReturn(false);
            CredentialConverter converter2 = mock(CredentialConverter.class);
            when(converter2.canConvert(metadata)).thenReturn(false);

            when(extensionListMock.stream()).thenReturn(Stream.of(converter1, converter2));

            // WHEN
            CredentialConverter result = CredentialConverter.findConverter(metadata);

            // THEN
            assertNull(result);
        }

        @Test
        @DisplayName("should return null if extension list is empty")
        void shouldReturnNullIfListIsEmpty() {
            // GIVEN
            when(extensionListMock.stream()).thenReturn(Stream.empty());

            // WHEN
            CredentialConverter result = CredentialConverter.findConverter(mock(BitwardenItemMetadata.class));

            // THEN
            assertNull(result);
        }
    }

    @Nested
    @DisplayName("findConverter(BitwardenItem)")
    class FindConverterByItem {
        @Test
        @DisplayName("should return the first matching converter")
        void shouldReturnFirstMatch() {
            // GIVEN
            BitwardenItem item = mock(BitwardenItem.class);
            CredentialConverter nonMatchingConverter = mock(CredentialConverter.class);
            when(nonMatchingConverter.canConvert(item)).thenReturn(false);
            CredentialConverter matchingConverter = mock(CredentialConverter.class);
            when(matchingConverter.canConvert(item)).thenReturn(true);
            CredentialConverter anotherConverter = mock(CredentialConverter.class);

            when(extensionListMock.stream())
                    .thenReturn(Stream.of(nonMatchingConverter, matchingConverter, anotherConverter));

            // WHEN
            CredentialConverter result = CredentialConverter.findConverter(item);

            // THEN
            assertSame(matchingConverter, result);
            verify(anotherConverter, never()).canConvert(item);
        }

        @Test
        @DisplayName("should return null if no converters match")
        void shouldReturnNullIfNoMatch() {
            // GIVEN
            BitwardenItem item = mock(BitwardenItem.class);
            CredentialConverter converter1 = mock(CredentialConverter.class);
            when(converter1.canConvert(item)).thenReturn(false);
            CredentialConverter converter2 = mock(CredentialConverter.class);
            when(converter2.canConvert(item)).thenReturn(false);

            when(extensionListMock.stream()).thenReturn(Stream.of(converter1, converter2));

            // WHEN
            CredentialConverter result = CredentialConverter.findConverter(item);

            // THEN
            assertNull(result);
        }
    }
}
