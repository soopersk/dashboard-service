package com.company.observability.service;

import com.company.observability.config.CalculatorProperties;
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
}
