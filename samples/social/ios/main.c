// The application's `main`.
//
// iOS starts a bundle at a C `main`, and everything this one does is hand over to the
// Rust side, which owns the loop here the way it does on a desktop: there is no Activity
// and no page, so the application is one executable with the renderer linked into it.
int dioxus_compose_ios_main(void);

int main(void) { return dioxus_compose_ios_main(); }
