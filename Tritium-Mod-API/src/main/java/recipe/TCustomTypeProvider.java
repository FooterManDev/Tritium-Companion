package recipe;

import java.util.Collection;

/**
 * Supplies dumped value instances for a Tritium custom value type.
 */
public interface TCustomTypeProvider
{
    TCustomTypeDescriptor getDescriptor();

    Collection<TCustomTypeEntry> dumpValues(Object server);
}
