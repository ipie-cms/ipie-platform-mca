package in.gov.ipie.common.utils.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

class IdGeneratorTest {

    private static final int SAMPLE = 20_000;

    /**
     * The bug this exists for: {@code rand_a} is twelve bits, not sixteen, because the version
     * nibble sits at bits 12-15. Masking the first entropy byte to 0xFF instead of 0x0F overlapped
     * them and ORed the version upward - 0x7 | 0x8 is 0xF - so roughly half of all ids were version
     * 15 while still looking like plausible UUIDs everywhere they were printed or stored. Only a
     * check of the version nibble catches it.
     */
    @Test
    void everyIdIsAVersion7Uuid() {
        for (int i = 0; i < SAMPLE; i++) {
            assertThat(IdGenerator.newUuid().version()).isEqualTo(7);
        }
    }

    @Test
    void everyIdCarriesTheRfcVariant() {
        for (int i = 0; i < SAMPLE; i++) {
            // java.util.UUID reports 2 for the RFC 4122/9562 variant (the leading bits 10).
            assertThat(IdGenerator.newUuid().variant()).isEqualTo(2);
        }
    }

    @Test
    void theTimestampIsTheCurrentTimeInTheHighBits() {
        long before = System.currentTimeMillis();
        long embedded = IdGenerator.newUuid().getMostSignificantBits() >>> 16;
        long after = System.currentTimeMillis();

        assertThat(embedded).isBetween(before, after);
    }

    @Test
    void idsGeneratedLaterSortAfterIdsGeneratedEarlier() throws InterruptedException {
        // The whole point of v7 over v4: lexicographic order tracks creation order, so inserts land
        // at the right-hand edge of the index instead of scattering across it.
        UUID first = IdGenerator.newUuid();
        Thread.sleep(2);
        UUID second = IdGenerator.newUuid();

        assertThat(first.toString()).isLessThan(second.toString());
        assertThat(first.compareTo(second)).isNegative();
    }

    @Test
    void idsAreUniqueUnderConcurrency() throws Exception {
        int threads = 8;
        int perThread = 5_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<List<UUID>>> tasks = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                tasks.add(() -> {
                    List<UUID> mine = new ArrayList<>(perThread);
                    for (int i = 0; i < perThread; i++) {
                        mine.add(IdGenerator.newUuid());
                    }
                    return mine;
                });
            }
            Set<UUID> all = new HashSet<>();
            for (Future<List<UUID>> future : pool.invokeAll(tasks)) {
                all.addAll(future.get());
            }
            // Ids minted in the same millisecond differ only in their 74 random bits; a collision
            // here would mean the entropy is not being drawn per call.
            assertThat(all).hasSize(threads * perThread);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theStringFormIsTheSameValue() {
        String id = IdGenerator.newId();

        assertThat(UUID.fromString(id).version()).isEqualTo(7);
    }
}
