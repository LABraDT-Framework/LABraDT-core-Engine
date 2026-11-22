package de.labradt.dt_engine.engine;

import com.fasterxml.jackson.databind.*;
import de.labradt.dt_engine.engine.model.Rule;
import org.junit.Test;
import org.springframework.http.*;
import org.springframework.web.client.*;
import java.util.*;
import static org.junit.Assert.*;

public class RuleTestRunsTest {
    private final String url="http://stub/services/trigger";
    private Rule rule(String actions) {
        Rule r=new Rule();r.setAttribute("idShort","Test");r.setAttribute("action",actions);r.setAttribute("targetElement","Temperature");return r;
    }
    private Map<String,Object> plan(boolean matched,long at) {
        return new LinkedHashMap<>(Map.of("conditionMet",matched,"dynamicArgs",Map.of(),"evaluatedAt",at));
    }
    private static class Client extends RestTemplate {
        String acknowledgement="{\"triggered\":[{\"executionId\":\"e1\",\"serviceId\":\"s1\"}]}";
        boolean pendingSecond,wrongId,offline;
        int posts;
        @Override public <T> ResponseEntity<T> postForEntity(String url,Object body,Class<T> type,Object... vars) {
            posts++;return ResponseEntity.accepted().body(type.cast(acknowledgement));
        }
        @Override public <T> T getForObject(String url,Class<T> type,Object... vars) {
            if(offline) throw new ResourceAccessException("offline");
            if(pendingSecond && url.endsWith("e2")) throw HttpClientErrorException.create(HttpStatus.NOT_FOUND,"waiting",HttpHeaders.EMPTY,null,null);
            try {
                String id=wrongId?"wrong":url.substring(url.lastIndexOf('=')+1);
                return type.cast(new ObjectMapper().readTree("{\"executionId\":\""+id+"\",\"status\":\"COMPLETED\",\"success\":true,\"outputData\":\"70\"}"));
            }catch(Exception error){throw new IllegalStateException(error);}
        }
    }
    @Test public void malformedAcknowledgementIsAVisibleFailureWithoutRetry() {
        RuleTestRuns runs=new RuleTestRuns();Client http=new Client();http.acknowledgement="{}";
        var result=runs.submit(plan(true,System.currentTimeMillis()),rule("s1"),http,url);
        assertEquals("FAILED",result.get("status"));assertTrue(result.get("error").toString().contains("may have started"));
        runs.read((String)result.get("testId"),http,url,(r,v)->true);assertEquals(1,http.posts);
    }
    @Test public void failureOfOneOutputRemainsFailureWhenAnotherOutputFinishesLater() {
        RuleTestRuns runs=new RuleTestRuns();Client http=new Client();
        http.acknowledgement="{\"triggered\":[{\"executionId\":\"e1\",\"serviceId\":\"s1\"},{\"executionId\":\"e2\",\"serviceId\":\"s2\"}]}";
        String id=(String)runs.submit(plan(true,System.currentTimeMillis()),rule("s1,s2"),http,url).get("testId");
        http.pendingSecond=true;assertEquals("RUNNING",runs.read(id,http,url,(r,v)->false).get("status"));
        http.pendingSecond=false;assertEquals("FAILED",runs.read(id,http,url,(r,v)->true).get("status"));assertEquals(1,http.posts);
    }
    @Test public void pollingFailureAndWrongCorrelationCanRecoverWithoutRetriggering() {
        RuleTestRuns runs=new RuleTestRuns();Client http=new Client();
        String id=(String)runs.submit(plan(true,System.currentTimeMillis()),rule("s1"),http,url).get("testId");
        http.offline=true;assertEquals("RUNNING",runs.read(id,http,url,(r,v)->true).get("status"));
        http.offline=false;http.wrongId=true;assertEquals("RUNNING",runs.read(id,http,url,(r,v)->true).get("status"));
        http.wrongId=false;assertEquals("COMPLETED",runs.read(id,http,url,(r,v)->true).get("status"));assertEquals(1,http.posts);
    }
    @Test public void pendingTestsExpireWithoutCancellingOrRetryingRemoteJobs() {
        RuleTestRuns runs=new RuleTestRuns();Client http=new Client();http.offline=true;
        String id=(String)runs.submit(plan(true,System.currentTimeMillis()-61000),rule("s1"),http,url).get("testId");
        assertEquals("TIMED_OUT",runs.read(id,http,url,(r,v)->true).get("status"));assertEquals(1,http.posts);
    }
    @Test public void boundedHistoryEvictsOnlyFinishedRecords() {
        RuleTestRuns runs=new RuleTestRuns();Client http=new Client();
        String first=(String)runs.submit(plan(false,System.currentTimeMillis()),rule("s1"),http,url).get("testId");
        for(int i=0;i<64;i++) runs.submit(plan(false,System.currentTimeMillis()),rule("s1"),http,url);
        try{runs.read(first,http,url,(r,v)->true);fail();}catch(NoSuchElementException expected){}
        assertEquals(0,http.posts);
    }
    @Test public void pendingLimitDoesNotLeakForeverWhenBrowserStopsPolling() {
        RuleTestRuns runs=new RuleTestRuns();Client http=new Client();
        for(int i=0;i<8;i++) runs.submit(plan(true,System.currentTimeMillis()),rule("s1"),http,url);
        try{runs.submit(plan(true,System.currentTimeMillis()),rule("s1"),http,url);fail();}catch(IllegalStateException expected){}
        assertEquals(8,http.posts);
        RuleTestRuns expired=new RuleTestRuns();
        String old=(String)expired.submit(plan(true,System.currentTimeMillis()-61000),rule("s1"),http,url).get("testId");
        expired.submit(plan(false,System.currentTimeMillis()),rule("s1"),http,url);
        assertEquals("TIMED_OUT",expired.read(old,http,url,(r,v)->true).get("status"));
    }
}
