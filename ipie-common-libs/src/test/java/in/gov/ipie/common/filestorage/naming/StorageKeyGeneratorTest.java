package in.gov.ipie.common.filestorage.naming;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StorageKeyGeneratorTest {

    @Test
    void newObjectPath_isStructuredByModuleEntityAndDocType_andEndsWithTheExtension() {
        String path = StorageKeyGenerator.newObjectPath("liquidation", "case-123", "court-order", ".pdf");

        assertThat(path).startsWith("liquidation/case-123/court-order/");
        assertThat(path).endsWith(".pdf");
    }

    @Test
    void newObjectPath_addsTheDotIfTheExtensionIsMissingIt() {
        String path = StorageKeyGenerator.newObjectPath("liquidation", "case-123", "court-order", "pdf");

        assertThat(path).endsWith(".pdf");
    }

    @Test
    void newObjectPath_neverContainsTheOriginalFilename() {
        // Only module/entity/docType/uuid.ext - no way for a caller-supplied filename to appear here.
        String path = StorageKeyGenerator.newObjectPath("liquidation", "case-123", "court-order", ".pdf");

        assertThat(path).doesNotContain("original", "filename");
    }

    @Test
    void quarantineAndPermanentKeys_shareTheSameObjectPathUnderDifferentPrefixes() {
        String objectPath = StorageKeyGenerator.newObjectPath("liquidation", "case-123", "court-order", ".pdf");

        assertThat(StorageKeyGenerator.quarantineKey(objectPath)).isEqualTo("quarantine/" + objectPath);
        assertThat(StorageKeyGenerator.permanentKey(objectPath)).isEqualTo("permanent/" + objectPath);
    }
}
