package com.nms.server.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which macros are credentials.
 *
 * <p>The names here are also the ones the V9 migration's regex is checked
 * against, so the two agree about what a secret is. They have to: the
 * migration classifies what is already stored and this classifies what arrives
 * next, and a macro that one calls secret and the other calls text would be
 * masked or not depending on when it happened to be created.
 */
class MacroTypeTest {

    @Test
    @DisplayName("credentials are secret whichever part of the name says so")
    void credentialsAreSecret() {
        assertThat(MacroType.defaultFor("{$CAMERA.PASSWORD}")).isEqualTo(MacroType.SECRET);
        assertThat(MacroType.defaultFor("{$SNMP.COMMUNITY}")).isEqualTo(MacroType.SECRET);
        assertThat(MacroType.defaultFor("{$API.KEY}")).isEqualTo(MacroType.SECRET);
        assertThat(MacroType.defaultFor("{$AUTH_TOKEN}")).isEqualTo(MacroType.SECRET);
        assertThat(MacroType.defaultFor("{$VAULT.CREDENTIAL.ID}")).isEqualTo(MacroType.SECRET);
        assertThat(MacroType.defaultFor("{$V3.PASSPHRASE}")).isEqualTo(MacroType.SECRET);
        assertThat(MacroType.defaultFor("{$KEY}")).isEqualTo(MacroType.SECRET);
    }

    /**
     * Whole segments, not substrings. Masking a setting an operator needs to
     * read back is a smaller harm than leaking a password, but it is still a
     * harm, and "contains KEY" would hit a surprising number of names.
     */
    @Test
    void settingsThatMerelyContainTheWordAreNot() {
        assertThat(MacroType.defaultFor("{$MONKEY.COUNT}")).isEqualTo(MacroType.TEXT);
        assertThat(MacroType.defaultFor("{$KEYSTORE.PATH}")).isEqualTo(MacroType.TEXT);
        assertThat(MacroType.defaultFor("{$TOKENISER}")).isEqualTo(MacroType.TEXT);
    }

    @Test
    void ordinarySettingsStayReadable() {
        assertThat(MacroType.defaultFor("{$CAMERA.USER}")).isEqualTo(MacroType.TEXT);
        assertThat(MacroType.defaultFor("{$CAMERA.RTSP.PATH}")).isEqualTo(MacroType.TEXT);
        assertThat(MacroType.defaultFor("{$CAMERA.HTTP.PORT}")).isEqualTo(MacroType.TEXT);
        assertThat(MacroType.defaultFor("{$IFNAME}")).isEqualTo(MacroType.TEXT);
    }

    @Test
    void caseAndBracketsDoNotMatter() {
        assertThat(MacroType.defaultFor("{$camera.password}")).isEqualTo(MacroType.SECRET);
        assertThat(MacroType.defaultFor("CAMERA.PASSWORD")).isEqualTo(MacroType.SECRET);
        assertThat(MacroType.defaultFor("  {$CAMERA.PASSWORD}  ")).isEqualTo(MacroType.SECRET);
    }

    /** A discovery reference is not part of the name being classified. */
    @Test
    void ignoresAContextReference() {
        assertThat(MacroType.defaultFor("{$IFNAME:\"eth0\"}")).isEqualTo(MacroType.TEXT);
        assertThat(MacroType.defaultFor("{$PASSWORD:\"cam1\"}")).isEqualTo(MacroType.SECRET);
    }

    @Test
    void survivesRubbish() {
        assertThat(MacroType.defaultFor(null)).isEqualTo(MacroType.TEXT);
        assertThat(MacroType.defaultFor("")).isEqualTo(MacroType.TEXT);
        assertThat(MacroType.defaultFor("{$}")).isEqualTo(MacroType.TEXT);
        assertThat(MacroType.defaultFor("{$.}")).isEqualTo(MacroType.TEXT);
    }
}
