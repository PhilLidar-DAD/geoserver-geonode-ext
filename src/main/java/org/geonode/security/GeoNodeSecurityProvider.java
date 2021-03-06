package org.geonode.security;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.logging.Level;

import org.apache.commons.dbcp.BasicDataSource;
import org.geoserver.catalog.Catalog;
import org.geoserver.platform.GeoServerExtensions;
import org.geoserver.platform.GeoServerResourceLoader;
import org.geoserver.security.GeoServerSecurityFilterChain;
import org.geoserver.security.GeoServerSecurityManager;
import org.geoserver.security.GeoServerSecurityProvider;
import org.geoserver.security.config.SecurityManagerConfig;
import org.geoserver.security.config.SecurityNamedServiceConfig;
import org.geoserver.security.validation.SecurityConfigException;
import org.geotools.util.logging.Logging;

public class GeoNodeSecurityProvider extends GeoServerSecurityProvider implements GeoNodeSecurityClient.Provider {

    private static Catalog catalog;
	private GeoNodeSecurityClient client;

    @Override
    public Class<GeoNodeAuthenticationProvider> getAuthenticationProviderClass() {
        return GeoNodeAuthenticationProvider.class;
    }

    @Override
    public GeoNodeAuthenticationProvider createAuthenticationProvider(
            SecurityNamedServiceConfig config)
    {
        client = configuredClient(((GeoNodeAuthProviderConfig)config).getBaseUrl());
        return new GeoNodeAuthenticationProvider(client);
    }

    @Override
    public Class<GeoNodeCookieProcessingFilter> getFilterClass() {
        return GeoNodeCookieProcessingFilter.class;
    }

    @Override
    public GeoNodeCookieProcessingFilter createFilter(SecurityNamedServiceConfig config) {
        return new GeoNodeCookieProcessingFilter();
    }

    public GeoNodeSecurityClient getSecurityClient() {
        return client;
    }

    protected GeoNodeSecurityClient configuredClient(String baseUrl) {
        HTTPClient httpClient = new HTTPClient(10, 10000, 10000);
        String securityClientDatabaseURL = GeoServerExtensions.getProperty("org.geonode.security.databaseSecurityClient.url");

        GeoNodeSecurityClient configured;
        String securityClient = "default";
        if (securityClientDatabaseURL == null) {
            DefaultSecurityClient defaultClient = new DefaultSecurityClient(baseUrl, httpClient);
            configured = defaultClient;
        } else {
            securityClient = "database";
            BasicDataSource dataSource = new BasicDataSource();
            dataSource.setDriverClassName("org.postgresql.Driver");
            dataSource.setUrl(securityClientDatabaseURL);
            configured = new DatabaseSecurityClient(dataSource, baseUrl, httpClient);
        }
        Logging.getLogger(getClass()).log(Level.INFO, "using geonode {0} security client", securityClient);
        return configured;
    }

    @Override
    public void init(GeoServerSecurityManager manager) {
    	try {
    		File cookie = geonodeCookie();
    		if (!cookie.exists()) {
    		    configureGeoNodeSecurity(manager);
    		    writeCookie(cookie);
    		}
		} catch (Exception e) {
			throw new RuntimeException("Failed to initialize GeoNode settings", e);
		}
    }

    private static File geonodeCookie() throws IOException {
    	GeoServerResourceLoader loader = getCatalog().getResourceLoader();
    	File geonodeDir = loader.findOrCreateDirectory("geonode");
    	return new File(geonodeDir, "geonode_initialized");
    }

    private static void writeCookie(File cookie) throws IOException {
    	FileWriter writer = new java.io.FileWriter(cookie);
    	writer.write("This file was created to flag that the GeoNode extensions have been configured in this server.");
    	writer.write("If you delete it, the GeoNode settings will be applied again the next time you restart GeoServer.");
    	writer.close();
    }

    private static void configureGeoNodeSecurity(GeoServerSecurityManager manager) throws Exception {
        addServices(manager);
        configureChains(manager);
    }

    private static void addServices(GeoServerSecurityManager manager)
        throws IOException, SecurityConfigException
    {
        GeoNodeAuthProviderConfig providerConfig = new GeoNodeAuthProviderConfig();
        providerConfig.setName("geonodeAuthProvider");
        providerConfig.setClassName(GeoNodeAuthenticationProvider.class.getCanonicalName());
        providerConfig.setBaseUrl("http://localhost/");
        manager.saveAuthenticationProvider(providerConfig);

        GeoNodeAuthFilterConfig filterConfig = new GeoNodeAuthFilterConfig();
        filterConfig.setName("geonodeCookieFilter");
        filterConfig.setClassName(GeoNodeCookieProcessingFilter.class.getCanonicalName());
        manager.saveFilter(filterConfig);

        GeoNodeAnonymousAuthFilterConfig anonymousFilterConfig = new GeoNodeAnonymousAuthFilterConfig();
        anonymousFilterConfig.setName("geonodeAnonymousFilter");
        anonymousFilterConfig.setClassName(GeoNodeAnonymousProcessingFilter.class.getCanonicalName());
        manager.saveFilter(anonymousFilterConfig);
    }

    private static void configureChains(GeoServerSecurityManager manager) throws Exception {
        SecurityManagerConfig config = manager.getSecurityConfig();
        config.getAuthProviderNames().add(0, "geonodeAuthProvider");


        GeoServerSecurityFilterChain filterChain = config.getFilterChain();

        // place the geonodeCookieFilter before the anonymous filter
        String[] geonodeCookieChains = {
            GeoServerSecurityFilterChain.WEB_CHAIN,
            GeoServerSecurityFilterChain.REST_CHAIN,
            GeoServerSecurityFilterChain.GWC_WEB_CHAIN,
            GeoServerSecurityFilterChain.GWC_REST_CHAIN,
            GeoServerSecurityFilterChain.DEFAULT_CHAIN
        };

        for (String chain : geonodeCookieChains) {
            filterChain.insertFirst(chain, "geonodeCookieFilter");
        }

        // stick the geonodeAnonymousFilter filter before the anonymous
        filterChain.insertBefore(GeoServerSecurityFilterChain.DEFAULT_CHAIN, "geonodeAnonymousFilter", GeoServerSecurityFilterChain.ANONYMOUS_FILTER);

        manager.saveSecurityConfig(config);
    }

    private static Catalog getCatalog() {
        if(catalog == null) {
            catalog = (Catalog) GeoServerExtensions.bean( "catalog");
        }
        return catalog;
    }
}
