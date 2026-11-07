package tw.townpass.assistant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;

import java.time.Clock;
import java.util.List;

@SpringBootApplication
public class AssistantApplication {
    public static void main(String[] args) {
        SpringApplication.run(AssistantApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ApplicationRunner seedCatalog(ServiceRepository repository, ObjectMapper mapper) {
        return args -> {
            try (var input = new ClassPathResource("services.json").getInputStream()) {
                List<ServiceEntry> entries = mapper.readValue(input, new TypeReference<>() {});
                repository.saveAll(entries);
            }
        };
    }
}
