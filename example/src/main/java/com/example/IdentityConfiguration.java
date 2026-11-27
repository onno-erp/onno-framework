package com.example;

import com.example.domain.catalogs.Employee;
import su.onno.identity.IdentityLink;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Links a signed-in login to its Employee row by email, so the person can be greeted, shown as a
 * comment author and routed tasks. The lookup reads the row directly, bypassing
 * the role grants.
 */
@Configuration
public class IdentityConfiguration {

    @Bean
    IdentityLink identity() {
        return IdentityLink.of(Employee.class, "email");
    }
}
