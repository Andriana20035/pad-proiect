package md.lab2.consumer;

import md.lab2.rpc.v1.Delivery;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class HardCrashOnceInjector implements ConsumerFailureInjector {
    private final Path markerFile;

    public HardCrashOnceInjector(Path markerFile) {
        this.markerFile = markerFile.toAbsolutePath().normalize();
    }

    @Override
    public void afterLocalPersist(Delivery delivery, ProcessResult result) {
        if (Files.exists(markerFile)) {
            return;
        }
        try {
            if (markerFile.getParent() != null) {
                Files.createDirectories(markerFile.getParent());
            }
            Files.writeString(markerFile, "crashed delivery=" + delivery.getDeliveryId());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create crash marker", e);
        }
        System.err.println(
                "HARD CRASH injected after local persistence and before PERSISTED ACK");
        Runtime.getRuntime().halt(17);
    }
}
