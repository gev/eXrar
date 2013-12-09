/*
 *  eXist Open Source Native XML Database
 *  Copyright (C) 2001-09 The eXist Project
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
 * $Id: UnTarFunction.java 13369 2010-12-11 17:35:48Z deliriumsky $
 */
package org.exist.xquery.modules.compression;

import com.github.junrar.Archive;
import com.github.junrar.exception.RarException;
import com.github.junrar.rarfile.FileHeader;
import org.exist.dom.QName;
import org.exist.util.MimeTable;
import org.exist.util.MimeType;
import org.exist.xmldb.EXistResource;
import org.exist.xmldb.LocalCollection;
import org.exist.xquery.*;
import org.exist.xquery.functions.xmldb.XMLDBAbstractCollectionManipulator;
import org.exist.xquery.modules.ModuleUtils;
import org.exist.xquery.value.*;
import org.exist.xquery.value.StringValue;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xmldb.api.base.Collection;
import org.xmldb.api.base.Resource;
import org.xmldb.api.base.XMLDBException;
import org.xmldb.api.modules.XMLResource;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;

/**
 * Extracts files and folders from a Rar file
 *
 * @author Adam Retter <adam@exist-db.org>
 * @author Evgeny Gazdovsky <gazdovsky@gmail.com>
 */
public class UnRarFunction extends BasicFunction {

    private FunctionReference entryFilterFunction;
    protected Sequence filterParam;
    private FunctionReference entryDataFunction;
    protected Sequence storeParam;
    private Sequence contextSequence;

    public final static FunctionSignature signatures[] = {
        new FunctionSignature(
            new QName("unrar", RarModule.NAMESPACE_URI, RarModule.PREFIX),
            "UnRar all the resources/folders from the provided data by calling user defined functions " +
            "to determine what and how to store the resources/folders.",
            new SequenceType[] {
                new FunctionParameterSequenceType("file", Type.STRING, Cardinality.EXACTLY_ONE, "The first rar volume. (Unraring is only available to the DBA role.)"),
                new FunctionParameterSequenceType("entry-filter", Type.FUNCTION_REFERENCE, Cardinality.EXACTLY_ONE,
                		"A user defined function for filtering resources from the rar file. The function takes 3 parameters e.g. " +
                		"user:unrar-entry-filter($path as xs:string, $data-type as xs:string, $param as item()*) as xs:boolean. " +
                		"$data-type may be 'resource' or 'folder'. $param is a sequence with any additional parameters, " +
                		"for example a list of extracted files. If the return type is true() it indicates the entry " +
                		"should be processed and passed to the entry-data function, else the resource is skipped."),
                new FunctionParameterSequenceType("entry-filter-param", Type.ANY_TYPE, Cardinality.ZERO_OR_MORE, "A sequence with an additional parameters for filtering function."),
                new FunctionParameterSequenceType("entry-data", Type.FUNCTION_REFERENCE, Cardinality.EXACTLY_ONE,
                		"A user defined function for storing an extracted resource from the rar file. The function takes 4 parameters e.g. " +
                		"user:unrar-entry-data($path as xs:string, $data-type as xs:string, $data as item()?, $param as item()*). " +
                		"Or a user defined function wich returns path for storing an extracted resource from the rar file. The function takes 3 parameters e.g. " +
                		"user:entry-path($path as xs:string, $data-type as xs:string, $param as item()*) as xs:anyURI. " +
                		"$data-type may be 'resource' or 'folder'. $param is a sequence with any additional parameters"),
                new FunctionParameterSequenceType("entry-data-param", Type.ANY_TYPE, Cardinality.ZERO_OR_MORE, "A sequence with an additional parameters for storing function."),
            },
            new SequenceType(Type.ITEM, Cardinality.ZERO_OR_MORE)
        )
    };

    public UnRarFunction(XQueryContext context, FunctionSignature signature)
    {
        super(context, signature);
    }

    @Override
    public Sequence eval(Sequence[] args, Sequence contextSequence) throws XPathException {

        if(args[0].isEmpty())
            return Sequence.EMPTY_SEQUENCE;

        if(!(args[1].itemAt(0) instanceof FunctionReference))
            throw new XPathException("No entry-filter function provided.");
        entryFilterFunction = (FunctionReference)args[1].itemAt(0);
        FunctionSignature entryFilterFunctionSig = entryFilterFunction.getSignature();
        if(entryFilterFunctionSig.getArgumentCount() < 3)
            throw new XPathException("entry-filter function must take at least 3 arguments.");

        filterParam = args[2];

        //get the entry-data function and check its types
        if(!(args[3].itemAt(0) instanceof FunctionReference))
            throw new XPathException("No entry-data function provided.");
        entryDataFunction = (FunctionReference)args[3].itemAt(0);
        FunctionSignature entryDataFunctionSig = entryDataFunction.getSignature();
        if(entryDataFunctionSig.getArgumentCount() < 3)
            throw new XPathException("entry-data function must take at least 3 arguments");

        storeParam = args[4];

        String path = args[0].itemAt(0).getStringValue();

        File archive;

        if (!context.getSubject().hasDbaRole()) {
            XPathException xPathException = new XPathException(this, "Permission denied, calling user '" + context.getSubject().getName() + "' must be a DBA to call this function.");
            LOG.error("Invalid user", xPathException);
            throw xPathException;
        }
        archive = new File(path);

        this.contextSequence = contextSequence;

        //get the entry-filter function and check its types
        try {
            return processCompressedData(archive);
        } catch (XMLDBException e) {
            throw new XPathException(e);
        }

    }

    protected Sequence processCompressedData(File archive) throws XPathException, XMLDBException
    {
        Archive arch = null;
        try {
            arch  = new Archive(archive);
            if (arch == null) {
                return Sequence.EMPTY_SEQUENCE;
            }
            if (arch.isEncrypted()) {
                LOG.warn("archive is encrypted cannot extract");
                return Sequence.EMPTY_SEQUENCE;
            }
            FileHeader fh;
            Sequence results = new ValueSequence();
            while (true) {
                fh = arch.nextFileHeader();
                if (fh == null) {
                    break;
                }
                if (fh.isEncrypted()) {
                    LOG.warn("file is encrypted cannot extract: "
                            + fh.getFileNameString());
                    continue;
                }
                Sequence processCompressedEntryResults = processCompressedEntry(arch, fh);
                results.addAll(processCompressedEntryResults);
            }
            return results;
        } catch (RarException e) {
            LOG.error(e.getMessage());
            throw new XPathException(e);
        } catch (IOException e) {
            LOG.error(e.getMessage());
            throw new XPathException(e);
        }
        finally
        {
            if(arch != null)
            {
                try
                {
                    arch.close();
                }
                catch(IOException ioe)
                {
                    LOG.warn(ioe.getMessage(), ioe);
                }
            }
        }
    }

    protected Sequence processCompressedEntry(Archive arch, FileHeader fh) throws IOException, XPathException, XMLDBException, RarException {
        String dataType = fh.isDirectory() ? "folder" : "resource";

        //call the entry-filter function
        Sequence filterParams[] = new Sequence[3];
        String name = fh.isUnicode() ? fh.getFileNameW() : fh.getFileNameString();
        filterParams[0] = new StringValue(name);
        filterParams[1] = new StringValue(dataType);
        filterParams[2] = filterParam;
        Sequence entryFilterFunctionResult = entryFilterFunction.evalFunction(contextSequence, null, filterParams);

        if(BooleanValue.FALSE == entryFilterFunctionResult.itemAt(0))
        {
            return Sequence.EMPTY_SEQUENCE;
        }
        else
        {
            Sequence entryDataFunctionResult;
            Sequence uncompressedData = Sequence.EMPTY_SEQUENCE;

            //copy the input data
            org.apache.commons.io.output.ByteArrayOutputStream baos = new org.apache.commons.io.output.ByteArrayOutputStream();
            arch.extractFile(fh, baos);
            byte[] entryData = baos.toByteArray();

            if (entryDataFunction.getSignature().getArgumentCount() == 3){

                Sequence dataParams[] = new Sequence[3];
                System.arraycopy(filterParams, 0, dataParams, 0, 2);
                dataParams[2] = storeParam;
                entryDataFunctionResult = entryDataFunction.evalFunction(contextSequence, null, dataParams);

                String path = entryDataFunctionResult.itemAt(0).getStringValue();

                Collection root = new LocalCollection(context.getUser(), context.getBroker().getBrokerPool(), new AnyURIValue("/db").toXmldbURI(), context.getAccessContext());

                if (fh.isDirectory()){

                    XMLDBAbstractCollectionManipulator.createCollection(root, path);

                } else {

                    Resource resource;

                    File file = new File(path);
                    name = file.getName();
                    path = file.getParent();

                    Collection target = (path==null) ? root : XMLDBAbstractCollectionManipulator.createCollection(root, path);

                    MimeType mime = MimeTable.getInstance().getContentTypeFor(name);

                    try{
                        NodeValue content = ModuleUtils.streamToXML(context, new ByteArrayInputStream(baos.toByteArray()));
                        resource = target.createResource(name, "XMLResource");
                        ContentHandler handler = ((XMLResource)resource).setContentAsSAX();
                        handler.startDocument();
                        content.toSAX(context.getBroker(), handler, null);
                        handler.endDocument();
                    } catch(SAXException e){
                        resource = target.createResource(name, "BinaryResource");
                        resource.setContent(baos.toByteArray());
                    }

                    if (resource != null){
                        if (mime != null){
                            ((EXistResource)resource).setMimeType(mime.getName());
                        }
                        target.storeResource(resource);
                    }

                }

            } else {

                //try and parse as xml, fall back to binary
                try
                {
                    uncompressedData = ModuleUtils.streamToXML(context, new ByteArrayInputStream(entryData));
                }
                catch(SAXException saxe)
                {
                    if(entryData.length > 0)
                        uncompressedData = BinaryValueFromInputStream.getInstance(context, new Base64BinaryValueType(), new ByteArrayInputStream(entryData));
                }

                //call the entry-data function
                Sequence dataParams[] = new Sequence[4];
                System.arraycopy(filterParams, 0, dataParams, 0, 2);
                dataParams[2] = uncompressedData;
                dataParams[3] = storeParam;
                entryDataFunctionResult = entryDataFunction.evalFunction(contextSequence, null, dataParams);

            }

            return entryDataFunctionResult;
        }
    }

}