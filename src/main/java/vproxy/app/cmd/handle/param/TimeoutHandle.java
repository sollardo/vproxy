package vproxy.app.cmd.handle.param;

import vproxy.app.cmd.Command;
import vproxy.app.cmd.Param;

public class TimeoutHandle {
    private TimeoutHandle() {
    }

    public static int get(Command cmd) throws Exception {
        int timeout = Integer.parseInt(cmd.args.get(Param.timeout));
        if (timeout < 0)
            throw new Exception("invalid timeout");
        return timeout;
    }
}
