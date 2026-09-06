package in.gov.ipie.common.resilience.config;

import java.io.IOException;
import java.util.Properties;

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.core.io.support.PropertySourceFactory;

/**
 * Lets {@code @PropertySource} load a YAML file. Needed because the defaults below live at
 * {@code ipie-resilience-defaults.yml} rather than the reserved {@code application.yml} name -
 * shipping an {@code application.yml} inside a library jar is fragile, since the classloader
 * resolves {@code classpath:/application.yml} to whichever single occurrence it finds first,
 * silently shadowing either this module's defaults or the consuming service's own file depending
 * on classpath order.
 */
public class YamlPropertySourceFactory implements PropertySourceFactory {

    @Override
    public PropertySource<?> createPropertySource(String name, EncodedResource resource) throws IOException {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(resource.getResource());
        Properties properties = factory.getObject();
        // getDescription() is documented to never return null, unlike getFilename() (which can
        // for non-file-backed resources) - avoids a possible null property-source name.
        String sourceName = (name == null || name.isEmpty()) ? resource.getResource().getDescription() : name;
        return new PropertiesPropertySource(sourceName, properties != null ? properties : new Properties());
    }
}
