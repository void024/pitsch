package com.pitsch.backend.auth;

import java.util.Map;

import com.pitsch.backend.activity.ActivityService;
import com.pitsch.backend.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) { }

    public record SignupRequest(@NotBlank String name, @NotBlank @Email String email,
                                @NotBlank @Size(min = 8, message = "must be at least 8 characters") String password) { }

    public record AuthResponse(String token, UserView user) { }

    private final UserRepository users;
    private final PasswordHasher hasher;
    private final JwtService jwt;
    private final ActivityService activity;

    public AuthController(UserRepository users, PasswordHasher hasher, JwtService jwt, ActivityService activity) {
        this.users = users;
        this.hasher = hasher;
        this.jwt = jwt;
        this.activity = activity;
    }

    @PostMapping("/signup")
    public ResponseEntity<AuthResponse> signup(@Valid @RequestBody SignupRequest req) {
        if (users.existsByEmailIgnoreCase(req.email())) {
            throw ApiException.conflict("An account with this email already exists.");
        }
        User user = new User();
        user.setName(req.name().trim());
        user.setEmail(req.email().trim().toLowerCase());
        user.setPasswordHash(hasher.hash(req.password()));
        users.save(user);
        activity.log(user.getId(), "SYSTEM", "Account created");
        return ResponseEntity.status(HttpStatus.CREATED).body(new AuthResponse(jwt.issue(user), UserView.of(user)));
    }

    @PostMapping({"/login", "/signin"})
    public AuthResponse login(@Valid @RequestBody LoginRequest req) {
        User user = users.findByEmailIgnoreCase(req.email().trim())
                .filter(u -> hasher.matches(req.password(), u.getPasswordHash()))
                .orElseThrow(() -> ApiException.unauthorized("Invalid email or password."));
        activity.log(user.getId(), "SYSTEM", "Signed in");
        return new AuthResponse(jwt.issue(user), UserView.of(user));
    }

    @GetMapping("/me")
    public UserView me(@RequestAttribute(AuthInterceptor.USER_ID) Long userId) {
        return users.findById(userId).map(UserView::of).orElseThrow(() -> ApiException.notFound("User"));
    }

    /** Tokens are stateless; the frontend just forgets it. Kept for API symmetry. */
    @PostMapping("/logout")
    public Map<String, Object> logout() {
        return Map.of("ok", true);
    }
}
