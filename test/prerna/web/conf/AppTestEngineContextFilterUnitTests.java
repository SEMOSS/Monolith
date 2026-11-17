package prerna.web.conf;

import static org.mockito.Mockito.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.*;
import prerna.auth.User;
import prerna.testing.EngineTestContexts;
import prerna.util.Constants;

class AppTestEngineContextFilterUnitTests {
    @Test void ordinaryRequestsPassThrough() throws Exception {
        var request=mock(HttpServletRequest.class);var response=mock(HttpServletResponse.class);var chain=mock(FilterChain.class);
        new AppTestEngineContextFilter().doFilter(request,response,chain);
        verify(chain).doFilter(request,response);verify(response,never()).setHeader(eq("X-Semoss-App-Test-Active"),anyString());
    }
    @Test void rejectsQueryTokenWithoutTheBrowserBridgeBeforeServingPortalFiles() throws Exception {
        var request=mock(HttpServletRequest.class);var response=mock(HttpServletResponse.class);var chain=mock(FilterChain.class);
        when(request.getParameter(EngineTestContexts.PARAMETER)).thenReturn("token");
        new PublicHomeCheckFilter().doFilter(request,response,chain);
        verify(response).sendError(eq(409),anyString());verifyNoInteractions(chain);
    }
    @Test void rejectsExpiredContextsBeforeRunningApplicationCode() throws Exception {
        var request=mock(HttpServletRequest.class);var response=mock(HttpServletResponse.class);var chain=mock(FilterChain.class);
        when(request.getHeader(EngineTestContexts.HEADER)).thenReturn("expired");
        try(var contexts=mockStatic(EngineTestContexts.class)) {
            contexts.when(() -> EngineTestContexts.enterRequest("expired",null,null)).thenThrow(new IllegalArgumentException("expired"));
            new AppTestEngineContextFilter().doFilter(request,response,chain);
            verify(response).sendError(eq(403),anyString());verifyNoInteractions(chain);
        }
    }
    @Test void entersAndClosesContextAndProtectsThePortalDocument() throws Exception {
        var request=mock(HttpServletRequest.class);var response=mock(HttpServletResponse.class);var chain=mock(FilterChain.class);
        var session=mock(HttpSession.class);var user=new User();var scope=mock(EngineTestContexts.Scope.class);
        when(request.getHeader(EngineTestContexts.HEADER)).thenReturn("token");when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute(Constants.SESSION_USER)).thenReturn(user);when(session.getId()).thenReturn("session");
        when(request.getContextPath()).thenReturn("/Monolith");when(request.getRequestURI()).thenReturn("/Monolith/public_home/app/portals/index.html");
        when(request.getScheme()).thenReturn("https");when(request.getServerName()).thenReturn("semoss.test");when(request.getServerPort()).thenReturn(443);
        try(var contexts=mockStatic(EngineTestContexts.class)) {
            contexts.when(() -> EngineTestContexts.enterRequest("token",user,"session")).thenReturn(scope);
            contexts.when(() -> EngineTestContexts.validate("token",user,"session")).thenReturn(new EngineTestContexts.Context("token","owner","session","app","profile",Map.of(),user,Long.MAX_VALUE));
            new AppTestEngineContextFilter().doFilter(request,response,chain);
            verify(chain).doFilter(request,response);verify(scope).close();
            verify(response).setHeader("X-Semoss-App-Test-Active","token");
            verify(response).setHeader(eq("Content-Security-Policy"),contains("worker-src 'none'"));
        }
    }
}
