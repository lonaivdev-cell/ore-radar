package net.loserver.oreradar.core.designation;

import java.util.List;

/**
 * A group before resolution: its id ({@code ores/<m>}, {@code custom/<key>}, {@code pack/<key>}) and the
 * designations whose blocks it unites.
 */
public record GroupDef(String id, List<Designation> designations) {
    public GroupDef {
        if (!TargetSpec.isValidGroupId(id)) throw new IllegalArgumentException("invalid group id " + id);
        designations = List.copyOf(designations);
    }
}
