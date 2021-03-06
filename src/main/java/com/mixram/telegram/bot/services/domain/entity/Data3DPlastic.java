package com.mixram.telegram.bot.services.domain.entity;

import com.mixram.telegram.bot.services.domain.enums.Shop3D;
import com.mixram.telegram.bot.utils.databinding.JsonUtil;
import com.mixram.telegram.bot.utils.htmlparser.entity.ParseData;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * @author mixram on 2019-03-29.
 * @since 0.1.1.0
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Data3DPlastic implements Serializable {

    private static final long serialVersionUID = 0L;

    private Shop3D shop;
    private List<ParseData> data;
    private List<ParseData> brokenUrls;

    @Override
    public String toString() {
        return JsonUtil.toJson(this);
    }
}
