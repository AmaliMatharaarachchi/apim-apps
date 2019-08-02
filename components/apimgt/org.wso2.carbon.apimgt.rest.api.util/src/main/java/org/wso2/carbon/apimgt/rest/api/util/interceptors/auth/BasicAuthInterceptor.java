/*
 *  Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.apimgt.rest.api.util.interceptors.auth;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.cxf.configuration.security.AuthorizationPolicy;
import org.apache.cxf.interceptor.security.AuthenticationException;
import org.apache.cxf.message.Message;
import org.apache.cxf.phase.AbstractPhaseInterceptor;
import org.apache.cxf.phase.Phase;
import org.wso2.carbon.CarbonException;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.Scope;
import org.wso2.carbon.apimgt.api.model.URITemplate;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.apimgt.rest.api.util.RestApiConstants;
import org.wso2.carbon.apimgt.rest.api.util.utils.RestApiUtil;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.core.util.AnonymousSessionUtil;
import org.wso2.carbon.registry.core.service.RegistryService;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.user.core.UserRealm;
import org.wso2.carbon.user.core.service.RealmService;
import org.wso2.carbon.utils.multitenancy.MultitenantUtils;
import org.wso2.uri.template.URITemplateException;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.cache.Caching;

/**
 * This class will validate incoming requests with Basic authenticator headers. This will also validate the roles of
 * the user based on the scopes attached to the API resources.
 * You can place this handler name in your web application if you need Basic authentication.
 */
public class BasicAuthInterceptor extends AbstractPhaseInterceptor {

    private static final Log log = LogFactory.getLog(BasicAuthInterceptor.class);

    public BasicAuthInterceptor() {
        //We will use PRE_INVOKE phase as we need to process message before hit actual service
        super(Phase.PRE_INVOKE);
    }

    /**
     * This method handles the incoming message by checking if an anonymous api is being called or invalid
     * authorization headers are present in the request. If not, authenticate the request.
     *
     * @param inMessage cxf Message
     */
    @Override
    public void handleMessage(Message inMessage) {
        //by-passes the interceptor if user calls an anonymous api
        if (RestApiUtil.checkIfAnonymousAPI(inMessage)) {
            return;
        }

        //Extract and check if "Authorization: Basic" is present in the request. If not, by-passes the interceptor. 
        //If yes, set the request_authentication_scheme property in the message as basic_auth and execute the basic 
        //authentication flow.
        AuthorizationPolicy policy = inMessage.get(AuthorizationPolicy.class);
        if (policy != null) {
            inMessage.put(RestApiConstants.REQUEST_AUTHENTICATION_SCHEME, RestApiConstants.BASIC_AUTHENTICATION);
            //Extract user credentials from the auth header and validate.
            String username = StringUtils.trim(policy.getUserName());
            String password = StringUtils.trim(policy.getPassword());
            if (StringUtils.isEmpty(username) || StringUtils.isEmpty(password)) {
                String errorMessage = StringUtils.isEmpty(username) ?
                        "username cannot be null/empty." : "password cannot be null/empty.";
                log.error("Basic Authentication failed: " + errorMessage);
                throw new AuthenticationException("Unauthenticated request");
            } else if (!authenticate(inMessage, username, password)) {
                throw new AuthenticationException("Unauthenticated request");
            }
            log.debug("User logged into web app using Basic Authentication");
        }
    }

    /**
     * This method authenticates the request using Basic authentication and validate the roles of user based on
     * roles of scope.
     *
     * @param inMessage cxf Message
     * @param username  username in basic auth header
     * @param password  password in basic auth header
     * @return true if user is successfully authenticated and authorized. false otherwise.
     */
    private boolean authenticate(Message inMessage, String username, String password) {
        PrivilegedCarbonContext carbonContext = PrivilegedCarbonContext.getThreadLocalCarbonContext();
        RealmService realmService = (RealmService) carbonContext.getOSGiService(RealmService.class, null);
        RegistryService registryService =
                (RegistryService) carbonContext.getOSGiService(RegistryService.class, null);
        String tenantDomain = MultitenantUtils.getTenantDomain(username);
        int tenantId;
        UserRealm userRealm;
        try {
            tenantId = realmService.getTenantManager().getTenantId(tenantDomain);
            userRealm = AnonymousSessionUtil.getRealmByTenantDomain(registryService, realmService, tenantDomain);
            if (userRealm == null) {
                log.error("Authentication failed: domain or unactivated tenant login");
                return false;
            }
            //if authenticated
            if (userRealm.getUserStoreManager()
                    .authenticate(MultitenantUtils.getTenantAwareUsername(username), password)) {
                //set the correct tenant info for downstream code.
                RestApiUtil.setThreadLocalRequestedTenant(username);
                carbonContext.setTenantDomain(tenantDomain);
                carbonContext.setTenantId(tenantId);
                carbonContext.setUsername(username);
                return validateRoles(inMessage, userRealm, tenantDomain, username);
            } else {
                log.error("Authentication failed: Invalid credentials");
            }
        } catch (UserStoreException | CarbonException e) {
            log.error("Error occurred while authenticating user: " + username, e);
        }
        return false;
    }

    /**
     * This method validates the roles of the user against the roles associated with the relevant scopes of the invoking
     * API request resource.
     *
     * @param inMessage    cxf Message
     * @param userRealm    UserRealm
     * @param tenantDomain tenant domain name
     * @param username     username
     * @return true if user is authorized, false otherwise.
     */
    private boolean validateRoles(Message inMessage, UserRealm userRealm, String tenantDomain, String username) {
        String basePath = (String) inMessage.get(Message.BASE_PATH);
        String path = (String) inMessage.get(Message.PATH_INFO);
        String verb = (String) inMessage.get(Message.HTTP_REQUEST_METHOD);
        String resource = path.substring(basePath.length() - 1);
        String[] userRoles;
        Map<String, String> restAPIScopes;
        //get all the URI templates of the REST API from the base path
        Set<URITemplate> uriTemplates = getURITemplatesForBasePath(basePath);

        //iterate through all the URITemplates to get the relevant URI template and get the scopes attached to validate
        for (Object template : uriTemplates.toArray()) {
            org.wso2.uri.template.URITemplate templateToValidate;
            Map<String, String> var = new HashMap<>();
            String templateString = ((URITemplate) template).getUriTemplate();
            try {
                templateToValidate = new org.wso2.uri.template.URITemplate(templateString);

                //check if the current URITemplate matches with the resource and verb of the API request
                if (templateToValidate.matches(resource, var) && verb != null
                        && verb.equalsIgnoreCase(((URITemplate) template).getHTTPVerb())) {

                    //get the scope list of the matched URITemplate
                    List<Scope> resourceScopeList = ((URITemplate) template).retrieveAllScopes();

                    //Continue the role check only if the invoking resource URI template has roles
                    if (!resourceScopeList.isEmpty()) {
                        //get the configured RESTAPIScopes map for the tenant from cache or registry
                        restAPIScopes = getRESTAPIScopesForTenant(tenantDomain);
                        if (restAPIScopes != null) {
                            //get the current role list of the user from local user store manager 
                            userRoles = userRealm.getUserStoreManager()
                                    .getRoleListOfUser(MultitenantUtils.getTenantAwareUsername(username));
                            if (userRoles != null) {
                                return validateUserRolesWithRESTAPIScopes(resourceScopeList, restAPIScopes,
                                        userRoles, username, path, verb);
                            } else {
                                log.error("Error while validating roles. Invalid user roles found for user: "
                                        + username);
                                return false;
                            }
                        } else {
                            //Error while getting the RESTAPIScopes
                            return false;
                        }
                    } else {
                        //Invoking resource has no scopes attached to it. Consider as anonymous permission.
                        if (log.isDebugEnabled()) {
                            log.debug("Scope not defined in swagger for matching resource " + resource + " and verb "
                                    + verb + ". So consider as anonymous permission and let request to continue.");
                        }
                        return true;
                    }
                }
            } catch (URITemplateException e) {
                log.error("Error while creating URI Template object to validate request. Template pattern: " +
                        templateString, e);
            } catch (UserStoreException e) {
                log.error("Error while getting role list of user: " + username, e);
            }
        }
        //No matching resource or verb found in swagger 
        log.error("Error while validating roles. No matching resource URI template found in swagger for resource "
                + resource + " and verb " + verb);
        return false;
    }

    /**
     * This method gets the RESTAPIScopes configuration from REST_API_SCOPE_CACHE if available, if not from
     * tenant-conf.json in registry.
     *
     * @param tenantDomain tenant domain name
     * @return Map of scopes which contains scope names and associated role list
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> getRESTAPIScopesForTenant(String tenantDomain) {
        Map<String, String> restAPIScopes;
        restAPIScopes = (Map) Caching.getCacheManager(APIConstants.API_MANAGER_CACHE_MANAGER)
                .getCache(RestApiConstants.REST_API_SCOPE_CACHE)
                .get(tenantDomain);
        if (restAPIScopes == null) {
            try {
                restAPIScopes =
                        APIUtil.getRESTAPIScopesFromConfig(APIUtil.getTenantRESTAPIScopesConfig(tenantDomain));
                //call load tenant config for rest API.
                //then put cache
                Caching.getCacheManager(APIConstants.API_MANAGER_CACHE_MANAGER)
                        .getCache(RestApiConstants.REST_API_SCOPE_CACHE)
                        .put(tenantDomain, restAPIScopes);
            } catch (APIManagementException e) {
                log.error("Error while getting REST API scopes for tenant: " + tenantDomain, e);
            }
        }
        return restAPIScopes;
    }

    /**
     * This method is used to get the URI template set for the relevant REST API using the given base path.
     *
     * @param basePath Base path of the REST API
     * @return Set of URI templates for the REST API
     */
    private Set<URITemplate> getURITemplatesForBasePath(String basePath) {
        Set<URITemplate> uriTemplates = new HashSet<>();
        //get URI templates using the base path in the request 
        if (basePath.contains(RestApiConstants.REST_API_PUBLISHER_CONTEXT_FULL_1)) {
            uriTemplates = RestApiUtil.getPublisherAppResourceMapping(RestApiConstants.REST_API_PUBLISHER_VERSION_1);
        } else if (basePath.contains(RestApiConstants.REST_API_STORE_CONTEXT_FULL_1)) {
            uriTemplates = RestApiUtil.getStoreAppResourceMapping(RestApiConstants.REST_API_STORE_VERSION_1);
        } else {
            String errorMessage = "No matching scope validation logic found for app request with path: " + basePath;
            log.error(errorMessage);
        }
        return uriTemplates;
    }

    /**
     * This method validates the user roles against the roles of the REST API scopes defined for the current resource.
     *
     * @param resourceScopeList Scope list of the current resource
     * @param restAPIScopes     RESTAPIScopes mapping for the current tenant
     * @param userRoles         Role list for the user
     * @param username          Username
     * @param path              Path Info
     * @param verb              HTTP Request Method
     * @return
     */
    private boolean validateUserRolesWithRESTAPIScopes(List<Scope> resourceScopeList, Map<String, String> restAPIScopes,
                                                       String[] userRoles, String username, String path, String verb) {
        //iterate the non empty scope list of the URITemplate of the invoking resource
        for (Scope scope : resourceScopeList) {
            //get the configured roles list string of the requested resource
            String resourceRolesString = restAPIScopes.get(scope.getKey());
            if (StringUtils.isNotBlank(resourceRolesString)) {
                //split role list string read using comma separator
                List<String> resourceRoleList = Arrays.asList(resourceRolesString.split("\\s*,\\s*"));
                //check if the roles related to the API resource contains any of the role of the user
                for (String role : userRoles) {
                    if (resourceRoleList.contains(role)) {
                        if (log.isDebugEnabled()) {
                            log.debug("Basic Authentication: scope validation successful for user: "
                                    + username + " with scope: " + scope.getKey()
                                    + " for resource path: " + path + " and verb " + verb);
                        }
                        return true;
                    }
                }
            } else {
                // No role for the requested resource scope
                if (log.isDebugEnabled()) {
                    log.debug("Role validation skipped. No REST API scope to role mapping defined for resource scope: "
                            + scope.getKey() + " Treated as anonymous scope.");
                }
                return true;
            }
        }
        log.error("Insufficient privileges. Role validation failed for user: "
                + username + " to access resource path: " + path + " and verb " + verb);
        return false;
    }

}
