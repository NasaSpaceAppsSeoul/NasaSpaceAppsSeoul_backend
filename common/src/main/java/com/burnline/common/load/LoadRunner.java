package com.burnline.common.load;

import com.burnline.common.config.BurnlineProperties;
import com.burnline.common.domain.Country;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * 시작할 때 적재.
 * <ul>
 *   <li>burnline.data.load=if-empty (기본): 아직 없는 나라만</li>
 *   <li>burnline.data.load=always: 전부 다시</li>
 *   <li>프로필 load: 전부 다시 적재하고 종료 (--spring.profiles.active=load)</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoadRunner implements ApplicationRunner {

    private final ObjectProvider<Loader> loaders;
    private final BurnlineProperties props;
    private final SourceFiles files;
    private final Environment env;
    private final ConfigurableApplicationContext context;

    @Override
    public void run(ApplicationArguments args) {
        Loader loader = loaders.getIfAvailable();
        if (loader == null) {
            return;
        }
        boolean exitAfter = env.acceptsProfiles(Profiles.of("load"));
        String mode = exitAfter ? "always" : props.data().load();
        if ("never".equals(mode)) {
            return;
        }
        if (!files.exists()) {
            log.warn("data dir not found, skip loading: {}", files.dir());
            if (exitAfter) {
                System.exit(SpringApplication.exit(context, () -> 1));
            }
            return;
        }
        for (Country c : Country.values()) {
            if ("always".equals(mode) || !loader.loaded(c)) {
                long start = System.currentTimeMillis();
                loader.load(c);
                log.info("loaded {} in {} ms", c, System.currentTimeMillis() - start);
            }
        }
        if (exitAfter) {
            System.exit(SpringApplication.exit(context, () -> 0));
        }
    }
}
