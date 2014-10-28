/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.cxf.binding.soap.interceptor;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.events.XMLEvent;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import org.apache.cxf.Bus;
import org.apache.cxf.annotations.SchemaValidation.SchemaValidationType;
import org.apache.cxf.binding.soap.Soap11;
import org.apache.cxf.binding.soap.Soap12;
import org.apache.cxf.binding.soap.SoapFault;
import org.apache.cxf.binding.soap.SoapHeader;
import org.apache.cxf.binding.soap.SoapMessage;
import org.apache.cxf.binding.soap.SoapVersion;
import org.apache.cxf.binding.soap.SoapVersionFactory;
import org.apache.cxf.common.i18n.Message;
import org.apache.cxf.common.logging.LogUtils;
import org.apache.cxf.common.util.StringUtils;
import org.apache.cxf.databinding.DataBinding;
import org.apache.cxf.headers.HeaderManager;
import org.apache.cxf.headers.HeaderProcessor;
import org.apache.cxf.helpers.DOMUtils;
import org.apache.cxf.helpers.ServiceUtils;
import org.apache.cxf.interceptor.Fault;
import org.apache.cxf.phase.Phase;
import org.apache.cxf.staxutils.PartialXMLStreamReader;
import org.apache.cxf.staxutils.StaxUtils;
import org.apache.cxf.staxutils.StaxUtils.StreamToDOMContext;
import org.apache.cxf.staxutils.W3CDOMStreamWriter;


public class ReadHeadersInterceptor extends AbstractSoapInterceptor {
    /**
     * 
     */
    public static class CheckClosingTagsInterceptor extends AbstractSoapInterceptor {
        public CheckClosingTagsInterceptor() {
            super(Phase.POST_LOGICAL);
        }
        
        /** {@inheritDoc}*/
        public void handleMessage(SoapMessage message) throws Fault {
            XMLStreamReader xmlReader = message.getContent(XMLStreamReader.class);
            if (xmlReader != null) {
                try {
                    while (xmlReader.hasNext()) {
                        if (xmlReader.next() == XMLStreamReader.END_DOCUMENT) {
                            return;
                        }
                    }
                } catch (XMLStreamException e) {
                    throw new SoapFault(e.getMessage(), e, 
                                        message.getVersion().getSender());
                }
            }
        }

    }

    private static final Logger LOG = LogUtils.getL7dLogger(ReadHeadersInterceptor.class);

    private Bus bus;
    private SoapVersion version;
    public ReadHeadersInterceptor(Bus b) {
        super(Phase.READ);
        bus = b;
    }
    public ReadHeadersInterceptor(Bus b, SoapVersion v) {
        super(Phase.READ);
        version = v;
        bus = b;
    }
    public ReadHeadersInterceptor(Bus b, String phase) {
        super(phase);
        bus = b;
    }

    public static SoapVersion readVersion(XMLStreamReader xmlReader, SoapMessage message) {
        String ns = xmlReader.getNamespaceURI();
        String lcname = xmlReader.getLocalName();
        if (ns == null || "".equals(ns)) {
            throw new SoapFault(new Message("NO_NAMESPACE", LOG, lcname),
                                Soap11.getInstance().getVersionMismatch());
        }

        SoapVersion soapVersion = SoapVersionFactory.getInstance().getSoapVersion(ns);
        if (soapVersion == null) {
            throw new SoapFault(new Message("INVALID_VERSION", LOG, ns, lcname),
                                Soap11.getInstance().getVersionMismatch());
        }

        if (!"Envelope".equals(lcname)) {
            throw new SoapFault(new Message("INVALID_ENVELOPE", LOG, lcname),
                                soapVersion.getSender());
        }
        message.setVersion(soapVersion);
        return soapVersion;
    }
    
    public void handleMessage(SoapMessage message) {
        if (isGET(message)) {
            LOG.fine("ReadHeadersInterceptor skipped in HTTP GET method");
            return;
        }

        /*
         * Reject OPTIONS, and any other noise that is not allowed in SOAP.
         */
        final String verb = (String) message.get(org.apache.cxf.message.Message.HTTP_REQUEST_METHOD);
        if (verb != null && !"POST".equals(verb)) {
            Fault formula405 = new Fault("HTTP verb was not GET or POST", LOG);
            formula405.setStatusCode(405);
            throw formula405;
        }

        XMLStreamReader xmlReader = message.getContent(XMLStreamReader.class);
        boolean closeNeeded = false;
        if (xmlReader == null) {
            InputStream in = message.getContent(InputStream.class);
            if (in == null) {
                throw new RuntimeException("Can't find input stream in message");
            }
            xmlReader = StaxUtils.createXMLStreamReader(in);
            closeNeeded = true;
        }

        try {
            if (xmlReader.getEventType() == XMLStreamConstants.START_ELEMENT 
                || xmlReader.nextTag() == XMLStreamConstants.START_ELEMENT) {
                
                SoapVersion soapVersion = readVersion(xmlReader, message);
                if (soapVersion == Soap12.getInstance()
                    && version == Soap11.getInstance()) {
                    message.setVersion(version);
                    throw new SoapFault(new Message("INVALID_11_VERSION", LOG),
                                        version.getVersionMismatch());                    
                }

                XMLStreamReader filteredReader = new PartialXMLStreamReader(xmlReader, message.getVersion()
                    .getBody());

                Node nd = message.getContent(Node.class);
                W3CDOMStreamWriter writer = message.get(W3CDOMStreamWriter.class);
                Document doc = null;
                if (writer != null) {
                    StaxUtils.copy(filteredReader, writer);
                    doc = writer.getDocument();
                } else if (nd instanceof Document) {
                    doc = (Document)nd;
                    StaxUtils.readDocElements(doc, doc, filteredReader, false, false);
                } else {
                    HeadersProcessor processor = new HeadersProcessor(soapVersion);
                    doc = processor.process(filteredReader);
                    if (doc != null) {
                        message.setContent(Node.class, doc);
                    }
                }

                // Find header
                if (doc != null) {
                    Element element = doc.getDocumentElement();
                    QName header = soapVersion.getHeader();
                    List<Element> elemList = DOMUtils.findAllElementsByTagNameNS(element,
                                                                                 header.getNamespaceURI(),
                                                                                 header.getLocalPart());
                    for (Element elem : elemList) {
                        Element hel = DOMUtils.getFirstElement(elem);
                        while (hel != null) {
                            // Need to add any attributes that are present on the parent element
                            // which otherwise would be lost.
                            if (elem.hasAttributes()) {
                                NamedNodeMap nnp = elem.getAttributes();
                                for (int ct = 0; ct < nnp.getLength(); ct++) {
                                    Node attr = nnp.item(ct);
                                    Node headerAttrNode = hel.hasAttributes() ? hel.getAttributes()
                                        .getNamedItemNS(attr.getNamespaceURI(), attr.getLocalName()) : null;

                                    if (headerAttrNode == null) {
                                        Attr attribute = hel.getOwnerDocument()
                                            .createAttributeNS(attr.getNamespaceURI(), attr.getNodeName());
                                        attribute.setNodeValue(attr.getNodeValue());
                                        hel.setAttributeNodeNS(attribute);
                                    }
                                }
                            }

                            HeaderProcessor p = bus == null ? null : bus.getExtension(HeaderManager.class)
                                .getHeaderProcessor(hel.getNamespaceURI());

                            Object obj;
                            DataBinding dataBinding = null;
                            if (p == null || p.getDataBinding() == null) {
                                obj = hel;
                            } else {
                                dataBinding = p.getDataBinding();
                                obj = dataBinding.createReader(Node.class).read(hel);
                            }
                            // TODO - add the interceptors

                            SoapHeader shead = new SoapHeader(new QName(hel.getNamespaceURI(),
                                                                        hel.getLocalName()), obj, dataBinding);
                            String mu = hel.getAttributeNS(soapVersion.getNamespace(),
                                                           soapVersion.getAttrNameMustUnderstand());
                            String act = hel.getAttributeNS(soapVersion.getNamespace(),
                                                            soapVersion.getAttrNameRole());

                            if (!StringUtils.isEmpty(act)) {
                                shead.setActor(act);
                            }
                            shead.setMustUnderstand(Boolean.valueOf(mu) || "1".equals(mu));
                            // mark header as inbound header.(for distinguishing between the direction to
                            // avoid piggybacking of headers from request->server->response.
                            shead.setDirection(SoapHeader.Direction.DIRECTION_IN);
                            message.getHeaders().add(shead);

                            hel = DOMUtils.getNextElement(hel);
                        }
                    }
                }

                if (ServiceUtils.isSchemaValidationEnabled(SchemaValidationType.IN, message)) {
                    message.getInterceptorChain().add(new CheckClosingTagsInterceptor());
                }
            }
        } catch (XMLStreamException e) {
            throw new SoapFault(new Message("XML_STREAM_EXC", LOG, e.getMessage()), e, 
                                message.getVersion().getSender());
        } finally {
            if (closeNeeded) {
                try {
                    StaxUtils.close(xmlReader);
                } catch (XMLStreamException e) {
                    throw new SoapFault(new Message("XML_STREAM_EXC", LOG, e.getMessage()), e, 
                                        message.getVersion().getSender());
                }
            }
        }
    }

    /**
     * A convenient class for parsing the message header stream into a DOM document;
     * the document is created only if a SOAP Header is actually found, keeping the
     * memory usage as low as possible (there's no reason for building the DOM doc
     * here if there's actually no header in the message, but we need to figure that
     * out while parsing the stream).
     */
    private static class HeadersProcessor {
        private static final XMLEventFactory FACTORY = XMLEventFactory.newInstance();
        private final QName soapVersionHeader;
        private final List<XMLEvent> events = new ArrayList<XMLEvent>(8);
        private StreamToDOMContext context;
        private Document doc;
        private Node parent;

        public HeadersProcessor(SoapVersion version) {
            this.soapVersionHeader = version.getHeader();
        }

        public Document process(XMLStreamReader reader) throws XMLStreamException {
            // number of elements read in
            int read = 0;
            int event = reader.getEventType();
            while (reader.hasNext()) {
                switch (event) {
                case XMLStreamConstants.START_ELEMENT:
                    read++;
                    addEvent(FACTORY.createStartElement(new QName(reader.getNamespaceURI(), reader
                                                            .getLocalName(), reader.getPrefix()), null, null));
                    for (int i = 0; i < reader.getNamespaceCount(); i++) {
                        addEvent(FACTORY.createNamespace(reader.getNamespacePrefix(i),
                                                         reader.getNamespaceURI(i)));
                    }
                    for (int i = 0; i < reader.getAttributeCount(); i++) {
                        addEvent(FACTORY.createAttribute(reader.getAttributePrefix(i),
                                                         reader.getAttributeNamespace(i),
                                                         reader.getAttributeLocalName(i),
                                                         reader.getAttributeValue(i)));
                    }
                    if (doc != null) {
                        //go on parsing the stream directly till the end and stop generating events
                        StaxUtils.readDocElements(doc, parent, reader, context);
                    }
                    break;
                case XMLStreamConstants.END_ELEMENT:
                    if (read > 0) {
                        addEvent(FACTORY.createEndElement(new QName(reader.getNamespaceURI(), reader
                                                              .getLocalName(), reader.getPrefix()), null));
                    }
                    read--;
                    break;
                case XMLStreamConstants.CHARACTERS:
                    String s = reader.getText();
                    if (s != null) {
                        addEvent(FACTORY.createCharacters(s));
                    }
                    break;
                case XMLStreamConstants.COMMENT:
                    addEvent(FACTORY.createComment(reader.getText()));
                    break;
                case XMLStreamConstants.CDATA:
                    addEvent(FACTORY.createCData(reader.getText()));
                    break;
                case XMLStreamConstants.START_DOCUMENT:
                case XMLStreamConstants.END_DOCUMENT:
                case XMLStreamConstants.ATTRIBUTE:
                case XMLStreamConstants.NAMESPACE:
                    break;
                default:
                    break;
                }
                event = reader.next();
            }

            return doc;
        }

        private void addEvent(XMLEvent event) {
            if (event.isStartElement()) {
                QName qName = event.asStartElement().getName();
                if (soapVersionHeader.getLocalPart().equals(qName.getLocalPart())
                    && soapVersionHeader.getNamespaceURI().equals(qName.getNamespaceURI())) {
                    // process all events recorded so far
                    context = new StreamToDOMContext(true, false, false);
                    doc = DOMUtils.createDocument();
                    parent = doc;
                    try {
                        for (XMLEvent ev : events) {
                            parent = StaxUtils.readDocElement(doc, parent, ev, context);
                        }
                    } catch (XMLStreamException e) {
                        throw new Fault(e);
                    }
                } else {
                    events.add(event);
                }
            } else {
                events.add(event);
            }
        }
    }
}
