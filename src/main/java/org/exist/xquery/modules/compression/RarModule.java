/*
 *  eXist Open Source Native XML Database
 *  Copyright (C) 2007-2009 The eXist Project
 *  http://exist-db.org
 *
 *  This program is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public
 *  License along with this library; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 *
 * $Id: CompressionModule.java 13685 2011-01-29 17:30:17Z deliriumsky $
 */
package org.exist.xquery.modules.compression;

import org.apache.log4j.Logger;
import org.exist.xquery.AbstractInternalModule;
import org.exist.xquery.FunctionDef;

import java.util.List;
import java.util.Map;

/**
 * XQuery Extension module for compression and de-compression functions
 * 
 * @author Adam Retter <adam@exist-db.org>
 * @author ljo
 * @version 1.0
 */
public class RarModule extends AbstractInternalModule {

    private final static Logger logger = Logger.getLogger(RarModule.class);

    public final static String NAMESPACE_URI = "http://exist-db.org/xquery/compression/rar";

    public final static String PREFIX = "rar";
    public final static String INCLUSION_DATE = "2013-07-08";
    public final static String RELEASED_IN_VERSION = "eXist-2.1";

    private final static FunctionDef[] functions = {
        new FunctionDef(UnRarFunction.signatures[0], UnRarFunction.class)
    };

    public RarModule(Map<String, List<? extends Object>> parameters) {
        super(functions, parameters);
    }

    @Override
    public String getNamespaceURI() {
        return NAMESPACE_URI;
    }

    @Override
    public String getDefaultPrefix() {
        return PREFIX;
    }

    public String getDescription() {
        return "A module for compression and decompression RAR functions";
    }

    public String getReleaseVersion() {
        return RELEASED_IN_VERSION;
    }

}
