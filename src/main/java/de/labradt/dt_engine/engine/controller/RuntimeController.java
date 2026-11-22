package de.labradt.dt_engine.engine.controller;

import de.labradt.dt_engine.engine.StateChartExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import java.util.function.Supplier;

@RestController
@RequestMapping("/engine/runtime")
public class RuntimeController {
    private final StateChartExecutor executor;
    public RuntimeController(StateChartExecutor executor) { this.executor = executor; }
    @GetMapping public Map<String,Object> status() { return executor.runtimeStatus(); }
    @PostMapping("/start") public Map<String,Object> start() { return checked(executor::startRuntime); }
    @PostMapping("/stop") public Map<String,Object> stop() { return executor.stopRuntime(); }
    @PostMapping("/validate") public Map<String,Object> validate() { return checked(executor::validateRuntime); }
    private Map<String,Object> checked(Supplier<Map<String,Object>> action) {
        try { return action.get(); }
        catch (IllegalStateException error) { throw new ResponseStatusException(HttpStatus.CONFLICT, error.getMessage()); }
        catch (IllegalArgumentException error) { throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, error.getMessage()); }
        catch (Exception error) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Backend validation failed: " + error.getMessage()); }
    }
}
