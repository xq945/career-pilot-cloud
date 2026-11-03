package com.careerpilot.service.optimization.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.careerpilot.service.optimization.coverage.RoleLexicon.Role;

class RoleLexiconTest {

    @Test
    void coversAllRequiredRoleCategories() {
        assertEquals(Set.of(
                Role.START_EMPLOYMENT, Role.END_EMPLOYMENT,
                Role.START_PROJECT, Role.END_PROJECT,
                Role.GRADUATION, Role.ENROLLMENT,
                Role.AWARD, Role.APPLICATION, Role.INTERNSHIP),
                RoleLexicon.roles());
    }

    @Test
    void longerAliasComesFirst() {
        List<RoleLexicon.Alias> aliases = RoleLexicon.aliases();
        int degree = indexOf(aliases, "取得学位");
        int obtain = indexOf(aliases, "取得");
        assertTrue(degree >= 0 && obtain >= 0);
        assertTrue(degree < obtain, "取得学位 必须先于 取得 匹配");
        assertEquals(Role.GRADUATION, aliases.get(degree).role());
        assertEquals(Role.AWARD, aliases.get(obtain).role());
    }

    @Test
    void englishAliasesAreMarkedAscii() {
        RoleLexicon.Alias joined = RoleLexicon.aliases().stream()
                .filter(alias -> alias.text().equals("joined"))
                .findFirst()
                .orElseThrow();
        assertTrue(joined.ascii());
        assertEquals(Role.START_EMPLOYMENT, joined.role());
    }

    @Test
    void unknownVerbsAreNotInLexicon() {
        assertFalse(RoleLexicon.aliases().stream()
                .anyMatch(alias -> alias.text().equals("调岗")));
        assertFalse(RoleLexicon.aliases().stream()
                .anyMatch(alias -> alias.text().equals("commenced")));
    }

    private static int indexOf(List<RoleLexicon.Alias> aliases, String text) {
        for (int i = 0; i < aliases.size(); i++) {
            if (aliases.get(i).text().equals(text)) {
                return i;
            }
        }
        return -1;
    }
}
