package com.company.observability.service;

import com.company.observability.config.CalculatorProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CalculatorNameResolverTest {

    CalculatorNameResolver resolver;

    @BeforeEach
    void setUp() {
        CalculatorProperties props = new CalculatorProperties();
        props.setAliases(Map.of(
                "capital", List.of("capitalcalc", "capitalcalcmedium"),
                "portfolio", List.of("portfoliocalc")
        ));
        resolver = new CalculatorNameResolver(props);
    }

    @Test
    void resolve_knownMultiAlias_returnsRealNames() {
        assertThat(resolver.resolve("capital"))
                .containsExactly("capitalcalc", "capitalcalcmedium");
    }

    @Test
    void resolve_knownSingleAlias_returnsSingleton() {
        assertThat(resolver.resolve("portfolio"))
                .containsExactly("portfoliocalc");
    }

    @Test
    void resolve_unknownName_passthroughAsSingleton() {
        assertThat(resolver.resolve("someothercalc"))
                .containsExactly("someothercalc");
    }

    @Test
    void resolveAll_expandsAliasesInOrder() {
        Map<String, List<String>> result = resolver.resolveAll(List.of("capital", "portfolio"));
        assertThat(result).containsOnlyKeys("capital", "portfolio");
        assertThat(result.get("capital")).containsExactly("capitalcalc", "capitalcalcmedium");
        assertThat(result.get("portfolio")).containsExactly("portfoliocalc");
    }

    @Test
    void resolveAll_preservesInsertionOrder() {
        Map<String, List<String>> result = resolver.resolveAll(List.of("portfolio", "capital"));
        assertThat(result.keySet()).containsExactly("portfolio", "capital");
    }

    @Test
    void findAliasFor_realNameBelongingToMultiAlias_returnsAlias() {
        assertThat(resolver.findAliasFor("capitalcalc")).contains("capital");
        assertThat(resolver.findAliasFor("capitalcalcmedium")).contains("capital");
    }

    @Test
    void findAliasFor_realNameBelongingToSingleAlias_returnsAlias() {
        assertThat(resolver.findAliasFor("portfoliocalc")).contains("portfolio");
    }

    @Test
    void findAliasFor_realNameNotInAnyAlias_empty() {
        assertThat(resolver.findAliasFor("unknowncalc")).isEmpty();
    }

    @Test
    void findAliasFor_null_empty() {
        assertThat(resolver.findAliasFor(null)).isEmpty();
    }

    @Test
    void isMultiAlias_multiEntry_true() {
        assertThat(resolver.isMultiAlias("capital")).isTrue();
    }

    @Test
    void isMultiAlias_singleEntry_false() {
        assertThat(resolver.isMultiAlias("portfolio")).isFalse();
    }

    @Test
    void isMultiAlias_unknownName_false() {
        assertThat(resolver.isMultiAlias("anycalc")).isFalse();
    }
}
