# Repository Guidance

## Core parity requirement

Any change that touches the Ishiiruka core must also be evaluated and reflected in the mainline core. Mainline is the priority core for this Android project, so new features, bug fixes, behavior changes, launch/runtime fixes, controller/input changes, replay/netplay work, and performance changes must either:

- update both the Ishiiruka and mainline core paths, or
- explicitly document why the change is impossible or irrelevant for mainline and how mainline was verified unaffected.

Do not treat an Ishiiruka-only fix as complete until the mainline path has been checked.
