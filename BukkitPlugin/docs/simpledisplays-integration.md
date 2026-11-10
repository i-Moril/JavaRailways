# Display integration ownership

SimpleDisplays integration now belongs to the independent [Decorations module](../../Decorations/README.md). JavaRailways exposes only generic namespaced extension parts, captured resources and lifecycle registration through `api.model.extension.PartExtensionService`.

Missing Decorations preserves train nodes, transforms, animations, children and captured resources. Current foundation work does not mount or render screens. Built-in `type: screen` and root `displays.yml` were unshipped development formats and have been replaced directly; there is no legacy runtime reader.
