package org.jeecg.monitor.config;

import de.codecentric.boot.admin.server.config.AdminServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

/**
 * @author scott
 */
@Configuration
public class SecuritySecureConfig {

    private static final String[] STATIC_PATTERNS = {"/css/**", "/js/**", "/image/**"};
    private static final String ACTUATOR_PATTERN = "/actuator/**";
    private static final String INSTANCES_PATTERN = "/instances";

    private final String adminContextPath;

    public SecuritySecureConfig(AdminServerProperties adminServerProperties) {
        this.adminContextPath = adminServerProperties.getContextPath();
    }

    @Bean
    public SecurityFilterChain configure(HttpSecurity http) throws Exception {
        SavedRequestAwareAuthenticationSuccessHandler successHandler = new SavedRequestAwareAuthenticationSuccessHandler();
        successHandler.setTargetUrlParameter("redirectTo");
        successHandler.setDefaultTargetUrl(adminContextPath + "/");

        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(adminContextPath + "/assets/**").permitAll()
                        .requestMatchers(adminContextPath + "/login").permitAll()
                        .requestMatchers(STATIC_PATTERNS).permitAll()
                        .anyRequest().authenticated()
                )
                .formLogin(formLogin -> formLogin.loginPage(adminContextPath + "/login").successHandler(successHandler))
                .logout(logout -> logout.logoutUrl(adminContextPath + "/logout"))
                .httpBasic(Customizer.withDefaults())
                .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .ignoringRequestMatchers(INSTANCES_PATTERN, ACTUATOR_PATTERN)
                );
        return http.build();
    }

}