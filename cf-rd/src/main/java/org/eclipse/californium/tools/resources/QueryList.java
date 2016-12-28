package org.eclipse.californium.tools.resources;

import java.util.HashMap;
import java.util.List;

/**
 *
 * @author Oliver Haase
 */
public class QueryList extends HashMap<String, String> {

    private QueryList() {
        super();
    }
    
    public static QueryList parse(List<String> uriQuery) {
        QueryList queryList = new QueryList();

        // Loop through the Query List and parse each Element
        for (String query : uriQuery) {
            
            // A Query has the format [key]=[value]
            String[] keyValue = query.split("=");
            // Do we just have a Key without Value?
            if (keyValue.length == 1) {
                queryList.put(keyValue[0], null);
            } else {
                queryList.put(keyValue[0], keyValue[1]);
            }
        }

        return queryList;
    }
}
