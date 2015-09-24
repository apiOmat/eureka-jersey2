package com.netflix.discovery.provider;

import com.fasterxml.jackson.annotation.JsonRootName;
import com.thoughtworks.xstream.annotations.XStreamAlias;

import java.io.Serializable;

@Serializer("com.netflix.discovery.converters.EntityBodyConverter")
@XStreamAlias("emptyEntity")
@JsonRootName("emptyEntity")
public class EmptyEntity implements Serializable {

}
