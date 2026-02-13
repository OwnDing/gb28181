package com.ownding.video.gb28181;

public record GbCatalogItem(
        String channelId,
        String name,
        String manufacturer,
        String model,
        String owner,
        String civilCode,
        String address,
        String parental,
        String parentId,
        String safetyWay,
        String registerWay,
        String secrecy,
        String status
) {
}
