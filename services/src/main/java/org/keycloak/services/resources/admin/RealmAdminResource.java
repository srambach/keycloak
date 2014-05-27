package org.keycloak.services.resources.admin;

import org.jboss.logging.Logger;
import org.jboss.resteasy.annotations.cache.NoCache;
import org.jboss.resteasy.spi.NotFoundException;
import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.keycloak.audit.AuditProvider;
import org.keycloak.audit.Event;
import org.keycloak.audit.EventQuery;
import org.keycloak.audit.EventType;
import org.keycloak.models.ApplicationModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ModelDuplicateException;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.provider.ProviderSession;
import org.keycloak.representations.adapters.action.SessionStats;
import org.keycloak.representations.idm.RealmAuditRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.services.managers.ModelToRepresentation;
import org.keycloak.services.managers.RealmManager;
import org.keycloak.services.managers.ResourceAdminManager;
import org.keycloak.services.managers.TokenManager;
import org.keycloak.services.resources.flows.Flows;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Base resource class for the admin REST api of one realm
 *
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
public class RealmAdminResource {
    protected static final Logger logger = Logger.getLogger(RealmAdminResource.class);
    protected RealmAuth auth;
    protected RealmModel realm;
    private TokenManager tokenManager;

    /*
    @Context
    protected ResourceContext resourceContext;
    */

    @Context
    protected KeycloakSession session;

    @Context
    protected ProviderSession providers;

    @Context
    protected UriInfo uriInfo;

    public RealmAdminResource(RealmAuth auth, RealmModel realm, TokenManager tokenManager) {
        this.auth = auth;
        this.realm = realm;
        this.tokenManager = tokenManager;

        auth.init(RealmAuth.Resource.REALM);
    }

    /**
     * Base path for managing applications under this realm.
     *
     * @return
     */
    @Path("applications")
    public ApplicationsResource getApplications() {
        ApplicationsResource applicationsResource = new ApplicationsResource(realm, auth);
        ResteasyProviderFactory.getInstance().injectProperties(applicationsResource);
        //resourceContext.initResource(applicationsResource);
        return applicationsResource;
    }

    /**
     * base path for managing oauth clients in this realm
     *
     * @return
     */
    @Path("oauth-clients")
    public OAuthClientsResource getOAuthClients() {
        OAuthClientsResource oauth = new OAuthClientsResource(realm, auth, session);
        ResteasyProviderFactory.getInstance().injectProperties(oauth);
        //resourceContext.initResource(oauth);
        return oauth;
    }

    /**
     * base path for managing realm-level roles of this realm
     *
     * @return
     */
    @Path("roles")
    public RoleContainerResource getRoleContainerResource() {
        return new RoleContainerResource(realm, auth, realm);
    }

    /**
     * Get the top-level representation of the realm.  It will not include nested information like User, Application, or OAuth
     * Client representations.
     *
     * @return
     */
    @GET
    @NoCache
    @Produces("application/json")
    public RealmRepresentation getRealm() {
        if (auth.hasView()) {
            return ModelToRepresentation.toRepresentation(realm);
        } else {
            auth.requireAny();

            RealmRepresentation rep = new RealmRepresentation();
            rep.setRealm(realm.getName());

            return rep;
        }
    }

    /**
     * Update the top-level information of this realm.  Any user, roles, application, or oauth client information in the representation
     * will be ignored.  This will only update top-level attributes of the realm.
     *
     * @param rep
     * @return
     */
    @PUT
    @Consumes("application/json")
    public Response updateRealm(final RealmRepresentation rep) {
        auth.requireManage();

        logger.debug("updating realm: " + realm.getName());
        try {
            new RealmManager(session).updateRealm(rep, realm);
            return Response.noContent().build();
        } catch (ModelDuplicateException e) {
            return Flows.errors().exists("Realm " + rep.getRealm() + " already exists");
        }
    }

    /**
     * Delete this realm.
     *
     */
    @DELETE
    public void deleteRealm() {
        auth.requireManage();

        if (!new RealmManager(session).removeRealm(realm)) {
            throw new NotFoundException("Realm doesn't exist");
        }
    }

    /**
     * Base path for managing users in this realm.
     *
     * @return
     */
    @Path("users")
    public UsersResource users() {
        UsersResource users = new UsersResource(providers, realm, auth, tokenManager);
        ResteasyProviderFactory.getInstance().injectProperties(users);
        //resourceContext.initResource(users);
        return users;
    }

    /**
     * Path for managing all realm-level or application-level roles defined in this realm by it's id.
     *
     * @return
     */
    @Path("roles-by-id")
    public RoleByIdResource rolesById() {
        RoleByIdResource resource = new RoleByIdResource(realm, auth);
        ResteasyProviderFactory.getInstance().injectProperties(resource);
        //resourceContext.initResource(resource);
        return resource;
    }

    /**
     * Push the realm's revocation policy to any application that has an admin url associated with it.
     *
     */
    @Path("push-revocation")
    @POST
    public void pushRevocation() {
        auth.requireManage();
        new ResourceAdminManager().pushRealmRevocationPolicy(uriInfo.getRequestUri(), realm);
    }

    /**
     * Removes all user sessions.  Any application that has an admin url will also be told to invalidate any sessions
     * they have.
     *
     */
    @Path("logout-all")
    @POST
    public void logoutAll() {
        auth.requireManage();
        realm.removeUserSessions();
        new ResourceAdminManager().logoutAll(uriInfo.getRequestUri(), realm);
    }

    /**
     * Remove a specific user session. Any application that has an admin url will also be told to invalidate this
     * particular session.
     *
     * @param sessionId
     */
    @Path("sessions/{session}")
    @DELETE
    public void deleteSession(@PathParam("session") String sessionId) {
        UserSessionModel session = realm.getUserSession(sessionId);
        if (session == null) throw new NotFoundException("Sesssion not found");
        realm.removeUserSession(session);
        new ResourceAdminManager().logoutSession(uriInfo.getRequestUri(), realm, session.getId());
    }

    /**
     * Returns a JSON map.  The key is the application name, the value is the number of sessions that currently are active
     * with that application.  Only application's that actually have a session associated with them will be in this map.
     *
     * @return
     */
    @Path("application-session-stats")
    @GET
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Integer> getApplicationSessionStats() {
        auth.requireView();
        Map<String, Integer> stats = new HashMap<String, Integer>();
        for (ApplicationModel applicationModel : realm.getApplications()) {
            int size = applicationModel.getActiveUserSessions();
            if (size == 0) continue;
            stats.put(applicationModel.getName(), size);
        }
        return stats;
    }

    /**
     * Any application that has an admin URL will be asked directly how many sessions they have active and what users
     * are involved with those sessions.
     *
     * @return
     */
    @Path("session-stats")
    @GET
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, SessionStats> getSessionStats() {
        logger.info("session-stats");
        auth.requireView();
        Map<String, SessionStats> stats = new HashMap<String, SessionStats>();
        for (ApplicationModel applicationModel : realm.getApplications()) {
            if (applicationModel.getManagementUrl() == null) continue;
            SessionStats appStats = new ResourceAdminManager().getSessionStats(uriInfo.getRequestUri(), realm, applicationModel, false);
            stats.put(applicationModel.getName(), appStats);
        }
        return stats;
    }

    /**
     * View the audit provider and how it is configured.
     *
     * @return
     */
    @GET
    @Path("audit")
    @Produces("application/json")
    public RealmAuditRepresentation getRealmAudit() {
        auth.init(RealmAuth.Resource.AUDIT).requireView();

        return ModelToRepresentation.toAuditReprensetation(realm);
    }

    /**
     * Change the audit provider and/or it's configuration
     *
     * @param rep
     */
    @PUT
    @Path("audit")
    @Consumes("application/json")
    public void updateRealmAudit(final RealmAuditRepresentation rep) {
        auth.init(RealmAuth.Resource.AUDIT).requireManage();

        logger.debug("updating realm audit: " + realm.getName());
        new RealmManager(session).updateRealmAudit(rep, realm);
    }

    /**
     * Query audit events.  Returns all events, or will query based on URL query parameters listed here
     *
     * @param client app or oauth client name
     * @param event event type
     * @param user user id
     * @param ipAddress
     * @param firstResult
     * @param maxResults
     * @return
     */
    @Path("audit/events")
    @GET
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public List<Event> getAudit(@QueryParam("client") String client, @QueryParam("event") String event, @QueryParam("user") String user,
                                @QueryParam("ipAddress") String ipAddress, @QueryParam("first") Integer firstResult, @QueryParam("max") Integer maxResults) {
        auth.init(RealmAuth.Resource.AUDIT).requireView();

        AuditProvider audit = providers.getProvider(AuditProvider.class);

        EventQuery query = audit.createQuery().realm(realm.getId());
        if (client != null) {
            query.client(client);
        }
        if (event != null) {
            query.event(EventType.valueOf(event));
        }
        if (user != null) {
            query.user(user);
        }
        if (ipAddress != null) {
            query.ipAddress(ipAddress);
        }
        if (firstResult != null) {
            query.firstResult(firstResult);
        }
        if (maxResults != null) {
            query.maxResults(maxResults);
        }

        return query.getResultList();
    }

    /**
     * Delete all audit events.
     *
     */
    @Path("audit/events")
    @DELETE
    public void clearAudit() {
        auth.init(RealmAuth.Resource.AUDIT).requireManage();

        AuditProvider audit = providers.getProvider(AuditProvider.class);
        audit.clear(realm.getId());
    }
}
