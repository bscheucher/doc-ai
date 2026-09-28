package com.learning.docai.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * Turns the Entra ID app roles of a token into authorities under their own name, so the rule
 * in {@link SecurityConfig} reads like the role does in Entra ID (SPEC §7). The scope
 * converter stays in front of it for the `scp` claim, which a delegated token would carry.
 */
public final class AppRoleAuthoritiesConverter
        implements Converter<Jwt, Collection<GrantedAuthority>> {

    /** Entra ID delivers app roles in this claim, one entry per role. */
    private static final String ROLES_CLAIM = "roles";

    private final JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = new ArrayList<>(scopes.convert(jwt));

        List<String> rollen = jwt.getClaimAsStringList(ROLES_CLAIM);
        if (rollen != null) {
            rollen.stream().map(SimpleGrantedAuthority::new).forEach(authorities::add);
        }
        return authorities;
    }
}
