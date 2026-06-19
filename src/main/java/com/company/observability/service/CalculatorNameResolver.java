package com.company.observability.service;

import com.company.observability.config.CalculatorProperties;
import com.company.observability.domain.enums.Dimension;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CalculatorNameResolver {

    private final CalculatorProperties calculatorProperties;

    /**
     * Resolves an alias to its list of real {@code calculator_name} values.
     * If the name is not a known alias, returns it as a singleton list (passthrough).
     */
    public List<String> resolve(String nameOrAlias) {
        List<String> mapped = calculatorProperties.getAliases().get(nameOrAlias);
        return mapped != null ? mapped : List.of(nameOrAlias);
    }

    /**
     * Expands a list of aliases/names to an ordered map of alias → real names,
     * preserving input order.
     */
    public Map<String, List<String>> resolveAll(List<String> aliases) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String alias : aliases) {
            result.put(alias, resolve(alias));
        }
        return result;
    }

    /**
     * Reverse lookup: given a real {@code calculator_name}, returns the alias it belongs to.
     * Returns empty if the name is not mapped under any alias.
     */
    public Optional<String> findAliasFor(String realName) {
        if (realName == null) return Optional.empty();
        return calculatorProperties.getAliases().entrySet().stream()
                .filter(e -> e.getValue().contains(realName))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    /**
     * Returns true if this alias expands to more than one real calculator name.
     */
    public boolean isMultiAlias(String nameOrAlias) {
        return resolve(nameOrAlias).size() > 1;
    }

    /**
     * Returns the primary dimension used to distinguish runs for this alias.
     * REGION for region-configured calculators, RUN_TYPE for type-configured, NONE otherwise.
     */
    public Dimension dimensionOf(String nameOrAlias) {
        if (calculatorProperties.getRegions().containsKey(nameOrAlias))  return Dimension.REGION;
        if (calculatorProperties.getRunTypes().containsKey(nameOrAlias)) return Dimension.RUN_TYPE;
        return Dimension.NONE;
    }

    /**
     * Returns true when the alias is declared in {@code run-number-aware}, meaning Airflow sends
     * a numbered cycle ({@code run_number=1}, {@code run_number=2}, …) and null-{@code run_number}
     * rows should be suppressed for strict cycle-scoped queries. {@code nameOrAlias} is matched as
     * an <b>alias</b> (the env-invariant key), not a real {@code calculator_name}.
     */
    public boolean isRunNumberAware(String nameOrAlias) {
        return calculatorProperties.getRunNumberAware().contains(nameOrAlias);
    }
}
