# Copied from Share Your Apps Server

These nine files are copies of Share Your Apps Server's own code — its HTTP server and the files it depends on, including the four for sharing a store — from its
`app/src/main/java/org/appdevforall/shareyourapps/server/`. The plugin's end-to-end test uses them to
serve a published store and verify it, exactly as the real server would.

They're used only by the tests and never ship in the plugin. When the server's versions change,
copy them here again, keeping the two comment lines at the top of each.
