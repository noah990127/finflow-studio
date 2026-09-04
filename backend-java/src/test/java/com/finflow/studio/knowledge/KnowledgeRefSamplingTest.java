package com.finflow.studio.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeRefSamplingTest {

    @Test
    void samplesAcrossTheWholeDocumentAndKeepsBothEnds() {
        var pages = IntStream.rangeClosed(1, 133).boxed().toList();

        var sampled = KnowledgeService.evenlySample(pages, 12);

        assertThat(sampled).hasSize(12);
        assertThat(sampled.getFirst()).isEqualTo(1);
        assertThat(sampled.getLast()).isEqualTo(133);
        assertThat(sampled).allSatisfy(page -> assertThat(page).isBetween(1, 133));
    }

    @Test
    void keepsShortDocumentsIntact() {
        assertThat(KnowledgeService.evenlySample(List.of(1, 2, 3), 10))
                .containsExactly(1, 2, 3);
    }
}
