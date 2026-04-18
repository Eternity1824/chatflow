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

    var data = {"OkPercent": 99.756, "KoPercent": 0.244};
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
    createTable($("#apdexTable"), {"supportsControllersDiscrimination": true, "overall": {"data": [0.99756, 500, 1500, "Total"], "isController": false}, "titles": ["Apdex", "T (Toleration threshold)", "F (Frustration threshold)", "Label"], "items": [{"data": [0.997078382815326, 500, 1500, "GET Room Messages 15min"], "isController": false}, {"data": [0.9976217812038708, 500, 1500, "GET User History Repeated"], "isController": false}, {"data": [0.9976679622431982, 500, 1500, "WS Open Connection"], "isController": false}, {"data": [0.9975819227882932, 500, 1500, "GET User Messages 15min"], "isController": false}, {"data": [0.9976507439310884, 500, 1500, "GET User Messages 5min"], "isController": false}, {"data": [0.9980636470786328, 500, 1500, "WS JOIN"], "isController": false}, {"data": [0.9974373259052924, 500, 1500, "GET User Rooms"], "isController": false}, {"data": [0.9975551480802356, 500, 1500, "GET Active Users"], "isController": false}, {"data": [0.9979762789148756, 500, 1500, "WS TEXT"], "isController": false}, {"data": [0.9974125093075205, 500, 1500, "GET Room Messages 5min"], "isController": false}, {"data": [0.9975283416820458, 500, 1500, "GET Room History Repeated"], "isController": false}]}, function(index, item){
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
    createTable($("#statisticsTable"), {"supportsControllersDiscrimination": true, "overall": {"data": ["Total", 300000, 732, 0.244, 181.89198333333516, 145, 500, 159.0, 382.0, 392.0, 483.0, 166.6313963544383, 1259.004041952613, 43.94123784163602], "isController": false}, "titles": ["Label", "#Samples", "FAIL", "Error %", "Average", "Min", "Max", "Median", "90th pct", "95th pct", "99th pct", "Transactions/s", "Received", "Sent"], "items": [{"data": ["GET Room Messages 15min", 35939, 105, 0.2921617184674031, 182.5047441498094, 145, 500, 159.0, 383.0, 392.0, 484.0, 19.96574507245145, 187.98954126379212, 5.247902920390905], "isController": false}, {"data": ["GET User History Repeated", 24388, 58, 0.2378218796129244, 181.85792192881826, 145, 500, 158.0, 382.0, 391.0, 483.0, 13.54995030166252, 127.30070376780209, 3.559663063363575], "isController": false}, {"data": ["WS Open Connection", 9005, 21, 0.23320377568017767, 180.60943920044423, 145, 499, 158.0, 169.0, 390.0, 482.0, 5.003711803981187, 0.9240434733580825, 1.3159927222873269], "isController": false}, {"data": ["GET User Messages 15min", 23986, 58, 0.2418077211706829, 181.89906612190526, 145, 500, 158.0, 169.0, 392.0, 484.0, 13.327169623876669, 125.57448689445444, 3.520870752506415], "isController": false}, {"data": ["GET User Messages 5min", 35756, 84, 0.23492560689115113, 181.72670321065038, 145, 500, 159.0, 169.0, 391.0, 483.0, 19.86101290277069, 186.17528313510834, 5.236032721395081], "isController": false}, {"data": ["WS JOIN", 11878, 23, 0.19363529213672334, 183.09353426502778, 145, 500, 158.0, 383.0, 392.0, 484.0, 6.59902453550434, 1.2200370143025163, 1.7403694331147364], "isController": false}, {"data": ["GET User Rooms", 17950, 46, 0.2562674094707521, 181.36278551531998, 145, 500, 159.0, 169.0, 391.0, 484.0, 9.971790111317398, 93.882181229612, 2.635290534979595], "isController": false}, {"data": ["GET Active Users", 17997, 44, 0.24448519197644053, 181.4727454575762, 145, 500, 158.0, 169.0, 391.0, 483.0, 9.997705699586637, 93.6940801164288, 2.633488646789118], "isController": false}, {"data": ["WS TEXT", 39037, 79, 0.20237210851243692, 181.69116479237766, 145, 500, 158.0, 382.0, 391.0, 484.0, 21.683945537118834, 4.021494087074064, 5.738234993968967], "isController": false}, {"data": ["GET Room Messages 5min", 53720, 139, 0.2587490692479523, 182.01029411764625, 145, 500, 158.0, 382.0, 392.0, 483.0, 29.83852647002789, 280.76544549567114, 7.877084629951171], "isController": false}, {"data": ["GET Room History Repeated", 30344, 75, 0.2471658317954126, 181.90363828104546, 145, 500, 159.0, 169.0, 391.0, 484.0, 16.85452859920893, 157.61637125110465, 4.442028875197392], "isController": false}]}, function(index, item){
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
    createTable($("#errorsTable"), {"supportsControllersDiscrimination": false, "titles": ["Type of error", "Number of errors", "% in errors", "% in all samples"], "items": [{"data": ["502/Server Error", 176, 24.043715846994534, 0.058666666666666666], "isController": false}, {"data": ["500/Server Error", 176, 24.043715846994534, 0.058666666666666666], "isController": false}, {"data": ["408/Server Error", 202, 27.595628415300546, 0.06733333333333333], "isController": false}, {"data": ["504/Server Error", 178, 24.316939890710383, 0.059333333333333335], "isController": false}]}, function(index, item){
        switch(index){
            case 2:
            case 3:
                item = item.toFixed(2) + '%';
                break;
        }
        return item;
    }, [[1, 1]]);

        // Create top5 errors by sampler
    createTable($("#top5ErrorsBySamplerTable"), {"supportsControllersDiscrimination": false, "overall": {"data": ["Total", 300000, 732, "408/Server Error", 202, "504/Server Error", 178, "502/Server Error", 176, "500/Server Error", 176, "", ""], "isController": false}, "titles": ["Sample", "#Samples", "#Errors", "Error", "#Errors", "Error", "#Errors", "Error", "#Errors", "Error", "#Errors", "Error", "#Errors"], "items": [{"data": ["GET Room Messages 15min", 35939, 105, "408/Server Error", 32, "500/Server Error", 26, "502/Server Error", 25, "504/Server Error", 22, "", ""], "isController": false}, {"data": ["GET User History Repeated", 24388, 58, "504/Server Error", 21, "500/Server Error", 14, "408/Server Error", 14, "502/Server Error", 9, "", ""], "isController": false}, {"data": ["WS Open Connection", 9005, 21, "502/Server Error", 7, "408/Server Error", 6, "500/Server Error", 4, "504/Server Error", 4, "", ""], "isController": false}, {"data": ["GET User Messages 15min", 23986, 58, "504/Server Error", 17, "408/Server Error", 16, "500/Server Error", 13, "502/Server Error", 12, "", ""], "isController": false}, {"data": ["GET User Messages 5min", 35756, 84, "502/Server Error", 27, "408/Server Error", 22, "500/Server Error", 18, "504/Server Error", 17, "", ""], "isController": false}, {"data": ["WS JOIN", 11878, 23, "500/Server Error", 8, "408/Server Error", 6, "504/Server Error", 6, "502/Server Error", 3, "", ""], "isController": false}, {"data": ["GET User Rooms", 17950, 46, "408/Server Error", 14, "500/Server Error", 12, "504/Server Error", 12, "502/Server Error", 8, "", ""], "isController": false}, {"data": ["GET Active Users", 17997, 44, "502/Server Error", 12, "504/Server Error", 12, "500/Server Error", 11, "408/Server Error", 9, "", ""], "isController": false}, {"data": ["WS TEXT", 39037, 79, "502/Server Error", 22, "408/Server Error", 21, "500/Server Error", 19, "504/Server Error", 17, "", ""], "isController": false}, {"data": ["GET Room Messages 5min", 53720, 139, "408/Server Error", 40, "500/Server Error", 35, "504/Server Error", 34, "502/Server Error", 30, "", ""], "isController": false}, {"data": ["GET Room History Repeated", 30344, 75, "408/Server Error", 22, "502/Server Error", 21, "500/Server Error", 16, "504/Server Error", 16, "", ""], "isController": false}]}, function(index, item){
        return item;
    }, [[0, 0]], 0);

});
