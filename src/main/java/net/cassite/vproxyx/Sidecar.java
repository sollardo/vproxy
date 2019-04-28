package net.cassite.vproxyx;

import net.cassite.vproxy.app.Application;
import net.cassite.vproxy.app.Config;
import net.cassite.vproxy.app.ServiceMeshResourceSynchronizer;
import net.cassite.vproxy.app.mesh.ServiceMeshMain;

public class Sidecar {
    public static void main0(@SuppressWarnings("unused") String[] args) {
        if (!Config.serviceMeshConfigProvided) {
            System.err.println("service mesh config not provided");
            System.exit(1);
            return;
        }
        int exitCode = ServiceMeshMain.getInstance().start();
        if (exitCode != 0) {
            System.exit(exitCode);
        }
        // start sync task
        Application.get().controlEventLoop.getSelectorEventLoop().period(10 * 1000,
            ServiceMeshResourceSynchronizer::sync);
        Config.configModifyDisabled = true;
    }
}
