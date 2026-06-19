package com.company.observability.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RunNumbersTest {

    @Test
    void null_returnsNull() {
        assertThat(RunNumbers.normalize(null)).isNull();
    }

    @Test
    void blank_returnsNull() {
        assertThat(RunNumbers.normalize("   ")).isNull();
    }

    @Test
    void emptyString_returnsNull() {
        assertThat(RunNumbers.normalize("")).isNull();
    }

    @Test
    void leadingZero_canonicalized() {
        assertThat(RunNumbers.normalize("01")).isEqualTo("1");
    }

    @Test
    void normalNumeric_unchanged() {
        assertThat(RunNumbers.normalize("2")).isEqualTo("2");
    }

    @Test
    void whitespace_trimmed() {
        assertThat(RunNumbers.normalize("  3  ")).isEqualTo("3");
    }

    @Test
    void nonNumeric_returnsTrimmedAsIs() {
        assertThat(RunNumbers.normalize("RUN1")).isEqualTo("RUN1");
    }
}
