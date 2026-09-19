package ra2;

import arc.Core;
import arc.struct.Seq;

/** Small shared helpers for building the "{0}×{1}、{2}…"列表文本 used by every batched report. */
final class Texts{
    private Texts(){
    }

    static String item(String displayName, int count){
        return Core.bundle.format("ra2ann.list.item", displayName, count);
    }

    static String list(Seq<String> parts, int moreTypes){
        StringBuilder builder = new StringBuilder();
        for(int i = 0; i < parts.size; i++){
            if(i > 0) builder.append(Core.bundle.get("ra2ann.list.separator", ", "));
            builder.append(parts.get(i));
        }
        if(moreTypes > 0){
            if(builder.length() > 0) builder.append(Core.bundle.get("ra2ann.list.separator", ", "));
            builder.append(Core.bundle.format("ra2ann.other.types", moreTypes));
        }
        return builder.toString();
    }
}
