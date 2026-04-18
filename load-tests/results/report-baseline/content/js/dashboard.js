/*
   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
var showControllersOnly = false;
var seriesFilter = "";
var filtersOnlySampleSeries = true;

/*
 * Add header in statistics table to group metrics by category
 * format
 *
 */
function summaryTableHeader(header) {
    var newRow = header.insertRow(-1);
    newRow.className = "tablesorter-no-sort";
    var cell = document.createElement('th');
    cell.setAttribute("data-sorter", false);
    cell.colSpan = 1;
    cell.innerHTML = "Requests";
    newRow.appendChild(cell);

    cell = document.createElement('th');
    cell.setAttribute("data-sorter", false);
    cell.colSpan = 3;
    cell.innerHTML = "Executions";
    newRow.appendChild(cell);

    cell = document.createElement('th');
    cell.setAttribute("data-sorter", false);
    cell.colSpan = 7;
    cell.innerHTML = "Response Times (ms)";
    newRow.appendChild(cell);

    cell = document.createElement('th');
    cell.setAttribute("data-sorter", false);
    cell.colSpan = 1;
    cell.innerHTML = "Throughput";
    newRow.appendChild(cell);

    cell = document.createElement('th');
    cell.setAttribute("data-sorter", false);
    cell.colSpan = 2;
    cell.innerHTML = "Network (KB/sec)";
    newRow.appendChild(cell);
}

/*
 * Populates the table identified by id parameter with the specified data and
 * format
 *
 */
function createTable(table, info, formatter, defaultSorts, seriesIndex, headerCreator) {
    var tableRef = table[0];

    // Create header and populate it with data.titles array
    var header = tableRef.createTHead();

    // Call callback is available
    if(headerCreator) {
        headerCreator(header);
    }

    var newRow = header.insertRow(-1);
    for (var index = 0; index < info.titles.length; index++) {
        var cell = document.createElement('th');
        cell.innerHTML = info.titles[index];
        newRow.appendChild(cell);
    }

    var tBody;

    // Create overall body if defined
    if(info.overall){
        tBody = document.createElement('tbody');
        tBody.className = "tablesorter-no-sort";
        tableRef.appendChild(tBody);
        var newRow = tBody.insertRow(-1);
        var data = info.overall.data;
        for(var index=0;index < data.length; index++){
            var cell = newRow.insertCell(-1);
            cell.innerHTML = formatter ? formatter(index, data[index]): data[index];
        }
    }

    // Create regular body
    tBody = document.createElement('tbody');
    tableRef.appendChild(tBody);

    var regexp;
    if(seriesFilter) {
        regexp = new RegExp(seriesFilter, 'i');
    }
    // Populate body with data.items array
    for(var index=0; index < info.items.length; index++){
        var item = info.items[index];
        if((!regexp || filtersOnlySampleSeries && !info.supportsControllersDiscrimination || regexp.test(item.data[seriesIndex]))
                &&
                (!showControllersOnly || !info.supportsControllersDiscrimination || item.isController)){
            if(item.data.length > 0) {
                var newRow = tBody.insertRow(-1);
                for(var col=0; col < item.data.length; col++){
                    var cell = newRow.insertCell(-1);
                    cell.innerHTML = formatter ? formatter(col, item.data[col]) : item.data[col];
                }
            }
        }
    }

    // Add support of columns sort
    table.tablesorter({sortList : defaultSorts});
}

$(document).ready(function() {

    // Customize table sorter default options
    $.extend( $.tablesorter.defaults, {
        theme: 'blue',
        cssInfoBlock: "tablesorter-no-sort",
        widthFixed: true,
        widgets: ['zebra']
    });

    var data = {"OkPercent": 99.605, "KoPercent": 0.395};
    var dataset = [
        {
            "label" : "FAIL",
            "data" : data.KoPercent,
            "color" : "#FF6347"
        },
        {
            "label" : "PASS",
            "data" : data.OkPercent,
            "color" : "#9ACD32"
        }];
    $.plot($("#flot-requests-summary"), dataset, {
        series : {
            pie : {
                show : true,
                radius : 1,
                label : {
                    show : true,
                    radius : 3 / 4,
                    formatter : function(label, series) {
                        return '<div style="font-size:8pt;text-align:center;padding:2px;color:white;">'
                            + label
                            + '<br/>'
                            + Math.round10(series.percent, -2)
                            + '%</div>';
                    },
                    background : {
                        opacity : 0.5,
                        color : '#000'
                    }
                }
            }
        },
        legend : {
            show : true
        }
    });

    // Creates APDEX table
    createTable($("#apdexTable"), {"supportsControllersDiscrimination": true, "overall": {"data": [0.946325, 500, 1500, "Total"], "isController": false}, "titles": ["Apdex", "T (Toleration threshold)", "F (Frustration threshold)", "Label"], "items": [{"data": [0.9478304489591172, 500, 1500, "GET Room Messages 15min"], "isController": false}, {"data": [0.9475107559926245, 500, 1500, "GET User History Repeated"], "isController": false}, {"data": [0.9502446982055465, 500, 1500, "WS Open Connection"], "isController": false}, {"data": [0.9451331778568798, 500, 1500, "GET User Messages 15min"], "isController": false}, {"data": [0.9438822065532974, 500, 1500, "GET User Messages 5min"], "isController": false}, {"data": [0.9451693851944794, 500, 1500, "WS JOIN"], "isController": false}, {"data": [0.9475235849056604, 500, 1500, "GET User Rooms"], "isController": false}, {"data": [0.9455580091727536, 500, 1500, "GET Active Users"], "isController": false}, {"data": [0.9458729533562145, 500, 1500, "GET Room Messages 5min"], "isController": false}, {"data": [0.9468093236530378, 500, 1500, "WS TEXT"], "isController": false}, {"data": [0.9466452712867315, 500, 1500, "GET Room History Repeated"], "isController": false}]}, function(index, item){
        switch(index){
            case 0:
                item = item.toFixed(3);
                break;
            case 1:
            case 2:
                item = formatDuration(item);
                break;
        }
        return item;
    }, [[0, 0]], 3);

    // Create statistics table
    createTable($("#statisticsTable"), {"supportsControllersDiscrimination": true, "overall": {"data": ["Total", 100000, 395, 0.395, 263.2054299999979, 218, 632, 232.0, 242.0, 549.0, 617.0, 3800.5472788081483, 28500.63655455448, 1004.4120865123327], "isController": false}, "titles": ["Label", "#Samples", "FAIL", "Error %", "Average", "Min", "Max", "Median", "90th pct", "95th pct", "99th pct", "Transactions/s", "Received", "Sent"], "items": [{"data": ["GET Room Messages 15min", 11961, 51, 0.4263857536995235, 262.08954100827646, 218, 632, 232.0, 242.0, 549.0, 616.0, 454.8081676109358, 4280.415508610118, 120.07979450264268], "isController": false}, {"data": ["GET User History Repeated", 8135, 23, 0.28272894898586354, 263.29723417332553, 218, 632, 232.0, 361.2000000001626, 549.0, 619.0, 309.24503915456546, 2874.4875886798636, 81.9618296443777], "isController": false}, {"data": ["WS Open Connection", 3065, 15, 0.4893964110929853, 259.7223491027728, 218, 631, 231.0, 242.0, 548.0, 609.0, 117.18152622725188, 21.589924097243465, 31.10506133869858], "isController": false}, {"data": ["GET User Messages 15min", 8147, 33, 0.4050570762243771, 263.9256167914576, 218, 632, 232.0, 540.0, 549.0, 616.0, 311.28687146568853, 2902.796471718344, 82.21826403217179], "isController": false}, {"data": ["GET User Messages 5min", 12055, 64, 0.5309000414765658, 263.9527167150561, 218, 632, 232.0, 540.0, 549.0, 617.4400000000005, 459.9916052962949, 4294.970913663334, 121.67811498359217], "isController": false}, {"data": ["WS JOIN", 3985, 13, 0.32622333751568383, 264.39322459222245, 218, 632, 232.0, 541.0, 549.0, 616.0, 152.01220675185962, 28.188551997901964, 40.48321869635705], "isController": false}, {"data": ["GET User Rooms", 5936, 18, 0.3032345013477089, 263.02762803234447, 218, 631, 232.0, 242.0, 549.0, 616.6300000000001, 226.38343312612028, 2114.4730428378016, 59.517463936348726], "isController": false}, {"data": ["GET Active Users", 5887, 23, 0.39069135383047393, 263.5116358077111, 218, 632, 232.0, 540.0, 549.0, 614.0, 224.35213414634148, 2095.9420553067835, 58.819617294683695], "isController": false}, {"data": ["GET Room Messages 5min", 17773, 63, 0.3544702638834187, 263.5973105272042, 218, 631, 232.0, 540.0, 549.0, 616.0, 677.3763244149707, 6320.782022674652, 179.53932958638234], "isController": false}, {"data": ["WS TEXT", 13085, 54, 0.4126862820022927, 262.9779136415743, 218, 632, 232.0, 242.0, 549.0, 618.0, 497.6231222665906, 92.25375546681877, 131.4721637906446], "isController": false}, {"data": ["GET Room History Repeated", 9971, 38, 0.38110520509477486, 263.0982850265772, 218, 632, 232.0, 242.0, 549.0, 615.0, 380.9214547677262, 3559.6065754412443, 100.34728108524985], "isController": false}]}, function(index, item){
        switch(index){
            // Errors pct
            case 3:
                item = item.toFixed(2) + '%';
                break;
            // Mean
            case 4:
            // Mean
            case 7:
            // Median
            case 8:
            // Percentile 1
            case 9:
            // Percentile 2
            case 10:
            // Percentile 3
            case 11:
            // Throughput
            case 12:
            // Kbytes/s
            case 13:
            // Sent Kbytes/s
                item = item.toFixed(2);
                break;
        }
        return item;
    }, [[0, 0]], 0, summaryTableHeader);

    // Create error table
    createTable($("#errorsTable"), {"supportsControllersDiscrimination": false, "titles": ["Type of error", "Number of errors", "% in errors", "% in all samples"], "items": [{"data": ["502/Server Error", 103, 26.075949367088608, 0.103], "isController": false}, {"data": ["500/Server Error", 91, 23.037974683544302, 0.091], "isController": false}, {"data": ["408/Server Error", 97, 24.556962025316455, 0.097], "isController": false}, {"data": ["504/Server Error", 104, 26.329113924050635, 0.104], "isController": false}]}, function(index, item){
        switch(index){
            case 2:
            case 3:
                item = item.toFixed(2) + '%';
                break;
        }
        return item;
    }, [[1, 1]]);

        // Create top5 errors by sampler
    createTable($("#top5ErrorsBySamplerTable"), {"supportsControllersDiscrimination": false, "overall": {"data": ["Total", 100000, 395, "504/Server Error", 104, "502/Server Error", 103, "408/Server Error", 97, "500/Server Error", 91, "", ""], "isController": false}, "titles": ["Sample", "#Samples", "#Errors", "Error", "#Errors", "Error", "#Errors", "Error", "#Errors", "Error", "#Errors", "Error", "#Errors"], "items": [{"data": ["GET Room Messages 15min", 11961, 51, "504/Server Error", 15, "502/Server Error", 14, "500/Server Error", 11, "408/Server Error", 11, "", ""], "isController": false}, {"data": ["GET User History Repeated", 8135, 23, "502/Server Error", 7, "504/Server Error", 6, "500/Server Error", 5, "408/Server Error", 5, "", ""], "isController": false}, {"data": ["WS Open Connection", 3065, 15, "408/Server Error", 5, "504/Server Error", 4, "502/Server Error", 3, "500/Server Error", 3, "", ""], "isController": false}, {"data": ["GET User Messages 15min", 8147, 33, "500/Server Error", 12, "502/Server Error", 7, "408/Server Error", 7, "504/Server Error", 7, "", ""], "isController": false}, {"data": ["GET User Messages 5min", 12055, 64, "500/Server Error", 18, "502/Server Error", 17, "504/Server Error", 16, "408/Server Error", 13, "", ""], "isController": false}, {"data": ["WS JOIN", 3985, 13, "502/Server Error", 6, "504/Server Error", 3, "500/Server Error", 2, "408/Server Error", 2, "", ""], "isController": false}, {"data": ["GET User Rooms", 5936, 18, "502/Server Error", 7, "408/Server Error", 6, "500/Server Error", 3, "504/Server Error", 2, "", ""], "isController": false}, {"data": ["GET Active Users", 5887, 23, "504/Server Error", 8, "500/Server Error", 6, "408/Server Error", 5, "502/Server Error", 4, "", ""], "isController": false}, {"data": ["GET Room Messages 5min", 17773, 63, "502/Server Error", 18, "408/Server Error", 16, "504/Server Error", 15, "500/Server Error", 14, "", ""], "isController": false}, {"data": ["WS TEXT", 13085, 54, "408/Server Error", 18, "504/Server Error", 15, "502/Server Error", 11, "500/Server Error", 10, "", ""], "isController": false}, {"data": ["GET Room History Repeated", 9971, 38, "504/Server Error", 13, "502/Server Error", 9, "408/Server Error", 9, "500/Server Error", 7, "", ""], "isController": false}]}, function(index, item){
        return item;
    }, [[0, 0]], 0);

});
