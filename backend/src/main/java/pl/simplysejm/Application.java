package pl.simplysejm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import pl.simplysejm.bootstrap.SejmProperties;

@SpringBootApplication
@EnableConfigurationProperties(SejmProperties.class)
public class Application {
    public static void main(String[] args) { SpringApplication.run(Application.class, args); }
}
