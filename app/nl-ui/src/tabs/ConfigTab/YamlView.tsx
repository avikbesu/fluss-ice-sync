import yaml from "js-yaml";
import { TableConfig } from "../../api/types";

/**
 * Client-side only, via `js-yaml` -- rendering the already-fetched JSON as
 * YAML text, no separate backend endpoint needed (the build prompt is
 * explicit about this: trino-nl-config-service doesn't need a YAML
 * response format of its own for this).
 */
export function YamlView({ config }: { config: TableConfig }) {
  const text = yaml.dump(config, { noRefs: true, sortKeys: false });
  return <pre className="yaml-view">{text}</pre>;
}
