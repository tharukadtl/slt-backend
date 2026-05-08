package lk.slt.fieldops.config;

import com.google.auth.oauth2
        .GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation
        .Value;
import org.springframework.context.annotation
        .Configuration;

import jakarta.annotation.PostConstruct;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

@Slf4j
@Configuration
public class FirebaseConfig {

    @Value("${app.firebase.credentials-path}")
    private String credentialsPath;

    @Value("${app.firebase.enabled}")
    private boolean firebaseEnabled;

    @Value("${app.firebase.project-id}")
    private String projectId;

    @PostConstruct
    public void initialize() {
        if (!firebaseEnabled) {
            log.info(
                    "Firebase disabled — "
                            + "push notifications "
                            + "will not be sent");
            return;
        }

        try {
            if (FirebaseApp.getApps().isEmpty()) {
                InputStream serviceAccount;

                // Try classpath first,
                // then file system
                try {
                    serviceAccount =
                            getClass()
                                    .getClassLoader()
                                    .getResourceAsStream(
                                            "firebase/"
                                                    + "serviceAccountKey.json");
                    if (serviceAccount == null) {
                        serviceAccount =
                                new FileInputStream(
                                        credentialsPath);
                    }
                } catch (IOException e) {
                    log.warn(
                            "Firebase credentials "
                                    + "not found at: {}. "
                                    + "Push notifications "
                                    + "disabled.",
                            credentialsPath);
                    return;
                }

                FirebaseOptions options =
                        FirebaseOptions.builder()
                                .setCredentials(
                                        GoogleCredentials
                                                .fromStream(
                                                        serviceAccount))
                                .setProjectId(
                                        projectId)
                                .build();

                FirebaseApp.initializeApp(options);
                log.info(
                        "Firebase initialized "
                                + "successfully for "
                                + "project: {}",
                        projectId);
            }
        } catch (Exception e) {
            log.error(
                    "Firebase initialization "
                            + "failed: {}. "
                            + "Push notifications "
                            + "will not work.",
                    e.getMessage());
        }
    }
}